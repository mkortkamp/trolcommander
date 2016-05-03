package com.mucommander.commons.file.archiver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.mucommander.commons.file.FileAttributes;
import com.mucommander.commons.file.impl.local.LocalFile;
import com.mucommander.commons.io.RandomAccessOutputStream;
import com.mucommander.commons.io.StreamUtils;
import com.mucommander.utils.ThrowingConsumer;
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
    public void startAsyncEntriesCreation() throws IOException {
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
                        public void setCompleted(long arg0) throws SevenZipException {
                            // TODO Auto-generated method stub

                        }

                        @Override
                        public void setTotal(long arg0) throws SevenZipException {
                            // TODO Auto-generated method stub

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

                            Runnable beforeProcessing = entry.getBeforeProcessing();
                            if (beforeProcessing != null) {
                                beforeProcessing.run();
                            }
                            final ThrowingConsumer<OutputStream, IOException> entryContentWriter = entry
                                    .getEntryContentWriter();
                            if (entryContentWriter != null) {

                                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                                try {
                                    entryContentWriter.accept(byteArrayOutputStream);
                                } catch (IOException e1) {
                                    throw new SevenZipException(e1);
                                }
                                return new InputStreamSequentialInStream(
                                        new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));
                            }
                            return null;
                        }

                        @Override
                        public void setOperationResult(boolean result) throws SevenZipException {
                            // TODO 7z exceptionhandling
                        }

                    });
        } catch (SevenZipException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void createEntryAsync(String entryPath, FileAttributes attributes, Runnable beforeProcessing,
            ThrowingConsumer<OutputStream, IOException> entryContentWriter) throws IOException {

        entries.add(new CreateEntryAsyncParameter(entryPath, attributes, beforeProcessing, entryContentWriter));
    }

    @Override
    protected OutputStream createEntry(String entryPath, FileAttributes attributes) throws IOException {
        throw new UnsupportedOperationException("use createEntryAsync instead!");
    }

    @Override
    public void postProcess() throws IOException {
        // nowhere used
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
