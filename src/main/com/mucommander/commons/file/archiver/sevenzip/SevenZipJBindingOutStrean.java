package com.mucommander.commons.file.archiver.sevenzip;

import java.io.IOException;
import com.mucommander.commons.io.RandomAccessOutputStream;
import net.sf.sevenzipjbinding.IOutStream;
import net.sf.sevenzipjbinding.SevenZipException;

public class SevenZipJBindingOutStrean implements IOutStream {

    private RandomAccessOutputStream randomAccessOut;

    public SevenZipJBindingOutStrean(RandomAccessOutputStream randomAccessOut) {
        super();
        this.randomAccessOut = randomAccessOut;
    }

    @Override
    public int write(byte[] bytes) throws SevenZipException {
        try {
            randomAccessOut.write(bytes);
        } catch (IOException e) {
            throw new SevenZipException(e);
        }
        return bytes.length;
    }
    @Override
    public long seek(long offset, int seekOrigin) throws SevenZipException {
        try {
            long offsetBeginning;

            switch (seekOrigin) {
                case SEEK_SET:
                    offsetBeginning = offset;
                    break;
                case SEEK_CUR:
                    offsetBeginning = randomAccessOut.getOffset() + offset;
                    break;
                case SEEK_END:
                    offsetBeginning = randomAccessOut.getLength() + offset;
                    break;
                default:
                    throw new SevenZipException(
                            new IllegalArgumentException("invalid seekOrigin: " + seekOrigin));
            }

            randomAccessOut.seek(offsetBeginning);

            return randomAccessOut.getOffset();

        } catch (IOException e) {
            throw new SevenZipException(e);
        }
    }
    @Override
    public void setSize(long newSize) throws SevenZipException {
        try {
            randomAccessOut.setLength(newSize);
        } catch (IOException e) {
            throw new SevenZipException(e);
        }
    }
}