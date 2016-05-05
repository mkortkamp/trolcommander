package com.mucommander.commons.file.archiver;

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
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.mucommander.commons.file.FileAttributes;
import com.mucommander.commons.file.impl.local.LocalFile;
import com.mucommander.commons.io.RandomAccessOutputStream;
import com.mucommander.commons.io.StreamUtils;
import com.mucommander.utils.ThrowingSupplier;
import net.sf.sevenzipjbinding.IOutCreateArchive7z;
import net.sf.sevenzipjbinding.IOutCreateCallback;
import net.sf.sevenzipjbinding.IOutItem7z;
import net.sf.sevenzipjbinding.IOutStream;
import net.sf.sevenzipjbinding.ISequentialInStream;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.InputStreamSequentialInStream;
import net.sf.sevenzipjbinding.impl.OutItemFactory;

public class SevenZipArchiver extends Archiver {

    private final IOutCreateArchive7z outArchive7z;
    private final List<CreateEntryAsyncParameter> entries = new ArrayList<>();
    private ExecutorService executor = Executors.newCachedThreadPool(); // TODO share this executor
    private Path tempFile;
    private OutputStream sequentialOut;

    private Object progressLock = new Object();
    private long totalWork;
    private long completedWork;

    SevenZipArchiver(OutputStream out) throws IOException {
        super(null);
        this.out = wrapSequentialOutputStream(out);
        try {
            outArchive7z = SevenZip.openOutArchive7z();
        } catch (SevenZipException e) {
            throw new IOException(e);
        }
    }

    private RandomAccessOutputStream wrapSequentialOutputStream(OutputStream outStream) throws IOException {
        if (outStream instanceof RandomAccessOutputStream) {
            return (RandomAccessOutputStream) outStream;
        } else {
            tempFile = Files.createTempFile("trolCommander-packer", "7z");
            tempFile.toFile().deleteOnExit();
            sequentialOut = outStream;
            return new LocalFile.LocalRandomAccessOutputStream(FileChannel.open(tempFile));
        }
    }

    @Override
    public Optional<Supplier<Float>> startAsyncEntriesCreation() throws IOException {
        try {
            outArchive7z.setLevel(9);
            outArchive7z.setTrace(true);
            outArchive7z.createArchive(new IOutStream() {

                @Override
                public int write(byte[] bytes) throws SevenZipException {
                    try {
                        out.write(bytes);
                    } catch (IOException e) {
                        throw new SevenZipException(e);
                    }
                    return bytes.length;
                }

                @Override
                public long seek(long offset, int seekOrigin) throws SevenZipException {
                    final RandomAccessOutputStream raOut = (RandomAccessOutputStream) out;
                    long offsetBeginning;
                    try {
                        switch (seekOrigin) {
                            case SEEK_SET:
                                offsetBeginning = offset;
                                break;
                            case SEEK_CUR:
                                offsetBeginning = raOut.getOffset() + offset;
                                break;
                            case SEEK_END:
                                offsetBeginning = raOut.getLength() + offset;
                                break;
                            default:
                                throw new SevenZipException(
                                        new IllegalArgumentException("invalid seekOrigin: " + seekOrigin));
                        }
                        raOut.seek(offsetBeginning);
                        return raOut.getOffset();
                    } catch (IOException e) {
                        throw new SevenZipException(e);
                    }
                }

                @Override
                public void setSize(long newSize) throws SevenZipException {
                    final RandomAccessOutputStream raOut = (RandomAccessOutputStream) out;
                    try {
                        raOut.setLength(newSize);
                    } catch (IOException e) {
                        throw new SevenZipException(e);
                    }
                }
            },
                    entries.size(),
                    new IOutCreateCallback<IOutItem7z>() {

                        private int currentIndex;

                        @Override
                        public void setCompleted(long complete) throws SevenZipException {
                            synchronized (progressLock) {
                                completedWork = complete;
                            }
                        }

                        @Override
                        public void setTotal(long total) throws SevenZipException {
                            synchronized (progressLock) {
                                totalWork = total;
                            }
                        }

                        @Override
                        public IOutItem7z getItemInformation(int index, OutItemFactory<IOutItem7z> factory)
                                throws SevenZipException {
                            currentIndex = index;
                            IOutItem7z outItem = factory.createOutItem();
                            final CreateEntryAsyncParameter entry = entries.get(index);
                            
                            outItem.setPropertyPath(entry.getEntryPath());
                            
                            final FileAttributes attributes = entry.getAttributes();
                            outItem.setPropertyIsDir(attributes.isDirectory());
                            outItem.setPropertyLastModificationTime(new Date(attributes.getDate()));
                            outItem.setDataSize(attributes.getSize());
                            // TODO 7z check all attributes
                            return outItem;
                        }

                        @Override
                        public ISequentialInStream getStream(int index) throws SevenZipException {
                            currentIndex = index;
                            final CreateEntryAsyncParameter entry = entries.get(index);

                            Supplier<Boolean> beforeProcessing = entry.getBeforeProcessing();
                            if (beforeProcessing != null && !beforeProcessing.get()) {
                                throw new SevenZipException("interrupted by user");
                            }
                            final ThrowingSupplier<InputStream, IOException> entrySourceSupplier = entry
                                    .getEntrySourceSupplier();
                            if (entrySourceSupplier != null) {
                                InputStream inputStream;
                                try {
                                    inputStream = entrySourceSupplier.get();
                                } catch (IOException e) {
                                    throw new SevenZipException(e);
                                }
                                if (inputStream != null) {
                                    return new InputStreamSequentialInStream(inputStream);
                                }
                            }
                            return null;
                        }

                        @Override
                        public void setOperationResult(boolean success) throws SevenZipException {
                            final CreateEntryAsyncParameter entry = entries.get(currentIndex);
                            Consumer<Optional<Exception>> onProcessingDone = entry.getOnProcessingDone();
                            if (onProcessingDone != null) {
                                onProcessingDone.accept(success ? Optional.empty()
                                        : Optional.of(new SevenZipException("item failed at index: " + currentIndex)));

                            }
                        }

                    });
        } catch (SevenZipException e) {
            throw new IOException(e);
        }

        return Optional.of(() -> {
            synchronized (progressLock) {
                if (totalWork == 0 || completedWork == 0) {
                    return -1.0F;
                }
                return (float) completedWork / totalWork;
            }
        });
    }

    @Override
    public void createEntryAsync(String entryPath, FileAttributes attributes, Supplier<Boolean> beforeProcessing,
            ThrowingSupplier<InputStream, IOException> entrySourceSupplier,
            Consumer<Optional<Exception>> onProcessingDone) {

        entries.add(new CreateEntryAsyncParameter(entryPath, attributes, beforeProcessing, entrySourceSupplier,
                onProcessingDone));
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
