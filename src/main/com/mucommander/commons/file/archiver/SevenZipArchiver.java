package com.mucommander.commons.file.archiver;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.commons.io.IOUtils;
import com.mucommander.commons.file.FileAttributes;
import com.mucommander.commons.file.archiver.sevenzip.CreateEntryAsyncParameter;
import com.mucommander.commons.file.archiver.sevenzip.SevenZipJBindingOutStrean;
import com.mucommander.commons.file.impl.local.LocalFile;
import com.mucommander.commons.io.BufferedRandomOutputStream;
import com.mucommander.commons.io.RandomAccessOutputStream;
import com.mucommander.commons.io.StreamUtils;
import com.mucommander.utils.ThrowingSupplier;
import net.sf.sevenzipjbinding.IOutCreateArchive7z;
import net.sf.sevenzipjbinding.IOutCreateCallback;
import net.sf.sevenzipjbinding.IOutItem7z;
import net.sf.sevenzipjbinding.ISequentialInStream;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.InputStreamSequentialInStream;
import net.sf.sevenzipjbinding.impl.OutItemFactory;

public class SevenZipArchiver extends Archiver {

    private final IOutCreateArchive7z outArchive7z;

    /**
     * file infos and callbacks for asynchronious operation
     */
    private final List<CreateEntryAsyncParameter> entries = new ArrayList<>();

    /**
     * tempFile for 7z output file - if out stream is sequential
     */
    private Path tempFile;
    /**
     * original out stream if it is sequential
     */
    private OutputStream sequentialOut;
    /**
     * random access out stream as needed by SevenZipJBinding - same as instance as {@link Archiver#out}
     */
    private RandomAccessOutputStream randomAccessOut;

    private long totalWork;
    private AtomicLong completedWork = new AtomicLong();

    SevenZipArchiver(OutputStream out) throws IOException {
        super(null);// out will be initialized below
        randomAccessOut = wrapSequentialOutputStream(out);
        this.out = randomAccessOut;

        try {
            outArchive7z = SevenZip.openOutArchive7z();
        } catch (SevenZipException e) {
            throw new IOException(e);
        }
    }

    private RandomAccessOutputStream wrapSequentialOutputStream(OutputStream outStream) throws IOException {
        if (outStream instanceof BufferedRandomOutputStream) {
            return (RandomAccessOutputStream) outStream;
        } else if (outStream instanceof RandomAccessOutputStream) {
            return new BufferedRandomOutputStream((RandomAccessOutputStream) outStream);
        } else {
            tempFile = Files.createTempFile("trolCommander-packer", "7z");
            tempFile.toFile().deleteOnExit();
            sequentialOut = outStream;
            final FileChannel fileChannel = FileChannel.open(tempFile);
            final LocalFile.LocalRandomAccessOutputStream raos = new LocalFile.LocalRandomAccessOutputStream(
                    fileChannel);
            return new BufferedRandomOutputStream(raos);
        }
    }

    @Override
    public Optional<Supplier<Float>> startAsyncEntriesCreation() throws IOException {
        try {
            outArchive7z.setLevel(9);
            outArchive7z.setSolid(true);
            outArchive7z.setTrace(false);
            outArchive7z.setThreadCount(4); // doesn't seem to havemuch performance impact

            outArchive7z.createArchive(new SevenZipJBindingOutStrean(randomAccessOut), entries.size(),
                    new IOutCreateCallback<IOutItem7z>() {

                        private int currentIndex;

                        @Override
                        public void setCompleted(long complete) throws SevenZipException {
                            completedWork.set(complete);
                        }

                        @Override
                        public void setTotal(long total) throws SevenZipException {
                            totalWork = total;
                        }

                        @Override
                        public IOutItem7z getItemInformation(int index, OutItemFactory<IOutItem7z> factory)
                                throws SevenZipException {
                            currentIndex = index;

                            CreateEntryAsyncParameter entry = entries.get(index);
                            FileAttributes entryAttributes = entry.getAttributes();
                            String entryPath = entry.getEntryPath();

                            IOutItem7z outItem7z = factory.createOutItem();

                            outItem7z.setPropertyPath(entryPath);
                            outItem7z.setPropertyIsDir(entryAttributes.isDirectory());
                            outItem7z.setPropertyLastModificationTime(new Date(entryAttributes.getDate()));
                            outItem7z.setDataSize(entryAttributes.getSize());

                            return outItem7z;
                        }

                        @Override
                        public ISequentialInStream getStream(int index) throws SevenZipException {
                            currentIndex = index;

                            CreateEntryAsyncParameter entry = entries.get(index);

                            Supplier<Boolean> beforeProcessing = entry.getBeforeProcessing();
                            if (beforeProcessing != null && !beforeProcessing.get()) {
                                throw new SevenZipException("interrupted by user");
                            }

                            ThrowingSupplier<InputStream, IOException> entrySourceSupplier = entry
                                    .getEntrySourceSupplier();

                            if (entrySourceSupplier != null) {
                                InputStream inputStream = null;
                                try {
                                    inputStream = entrySourceSupplier.get();
                                } catch (IOException e) {
                                    IOUtils.closeQuietly(inputStream);
                                    throw new SevenZipException(e);
                                }
                                if (inputStream != null) {
                                    return new InputStreamSequentialInStream(new BufferedInputStream(inputStream));
                                }
                            }
                            return null;
                        }

                        @Override
                        public void setOperationResult(boolean success) throws SevenZipException {

                            CreateEntryAsyncParameter entry = entries.get(currentIndex);
                            Consumer<Optional<Exception>> onProcessingDone = entry.getOnProcessingDone();

                            if (onProcessingDone != null) {
                                final Optional<Exception> result = success
                                        ? Optional.empty()
                                        : Optional.of(new SevenZipException("item failed at index: " + currentIndex));

                                onProcessingDone.accept(result);
                            }
                        }

                    }); // end craate archive callback
        } catch (SevenZipException e) {
            throw new IOException(e);
        }

        return Optional.of(() -> {
            long completed = completedWork.get();
            if (totalWork == 0 || completed == 0) {
                return -1.0F;
            }
            return (float) completed / totalWork;
        });
    }

    @Override
    public void createEntryAsync(String entryPath, FileAttributes attributes, Supplier<Boolean> beforeProcessing,
            ThrowingSupplier<InputStream, IOException> entrySourceSupplier,
            Consumer<Optional<Exception>> onProcessingDone) {

        CreateEntryAsyncParameter entry = new CreateEntryAsyncParameter(entryPath, attributes,
                beforeProcessing,
                entrySourceSupplier,
                onProcessingDone);

        entries.add(entry);
    }

    @Override
    protected OutputStream createEntry(String entryPath, FileAttributes attributes) throws IOException {
        throw new UnsupportedOperationException("use createEntryAsync instead!");
    }

    @Override
    public void postProcess() throws IOException {
        // not used with z-zip
    }

    @Override
    public void close() throws IOException {
        out.close();
        if (tempFile != null) {
            StreamUtils.copyStream(new FileInputStream(tempFile.toFile()), sequentialOut);
            tempFile.toFile().delete();
        }
    }
}
