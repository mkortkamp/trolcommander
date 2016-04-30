/*
 * This file is part of trolCommander, http://www.trolsoft.ru/soft/trolcommander
 * Copyright (C) 2013-2014 Oleg Trifonov
 *
 * muCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * muCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mucommander.commons.file.impl.sevenzip;

import com.mucommander.commons.file.AbstractFile;
import com.mucommander.commons.file.FileOperation;
import com.mucommander.commons.file.UnsupportedFileOperationException;
import com.mucommander.commons.io.RandomAccessInputStream;
import com.mucommander.commons.io.StreamUtils;
import net.sf.sevenzipjbinding.IInStream;
import net.sf.sevenzipjbinding.ISequentialInStream;
import net.sf.sevenzipjbinding.SevenZipException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.trolsoft.utils.StrUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

/**
 * @author Oleg Trifonov
 */
public class SignatureCheckedRandomAccessFile implements IInStream, ISequentialInStream {

    private static final Logger LOGGER = LoggerFactory.getLogger(SignatureCheckedRandomAccessFile.class);

    private AbstractFile file;

    private InputStream stream;

    private long position;

    private byte[] signature;

    public SignatureCheckedRandomAccessFile(AbstractFile file, byte[] signature)
            throws UnsupportedFileOperationException {
        super();
        this.signature = signature;
        this.position = 0;
        this.file = file;
        try {
            this.stream = openStreamAndCheckSignature(file);
        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.trace("Error", e);
            throw new UnsupportedFileOperationException(FileOperation.READ_FILE);
        }
    }

    @Override
    public long seek(long offset, int seekOrigin) throws SevenZipException {
        try {
            if (file.isFileOperationSupported(FileOperation.RANDOM_READ_FILE)) {
                seekOnRandomAccessFile(offset, seekOrigin);
            } else {
                seekOnSequentialFile(offset, seekOrigin);
            }
        } catch (IOException e) {
            throw new SevenZipException(e);
        }
        return position;
    }

    private void seekOnRandomAccessFile(long offset, int seekOrigin) throws IOException {
        RandomAccessInputStream randomAccessInputStream = (RandomAccessInputStream) stream;
        switch (seekOrigin) {
        case SEEK_SET:
            position = offset;
            break;
        case SEEK_CUR:
            position += offset;
            break;
        case SEEK_END:
            position = randomAccessInputStream.getLength() + offset;
            break;
        }
        randomAccessInputStream.seek(position);
    }

    /**
     * @param offset
     * @param seekOrigin
     * @throws IOException
     */
    private void seekOnSequentialFile(long offset, int seekOrigin) throws IOException {
        switch (seekOrigin) {
        case SEEK_SET:
            stream.close();
            stream = file.getInputStream();
            skip(offset);
            position = offset;
            break;
        case SEEK_CUR:
            skip(offset);
            position += offset;
            break;
        case SEEK_END:
            long size = file.getSize();
            if (size == -1) {
                skip(offset);
                size = position;
            }
            position = size + offset;
            stream.close();
            stream = file.getInputStream();
            stream.skip(position);
            break;
        }
    }

    /**
     * @param skip
     * @throws IOException
     */
    private void skip(long skip) throws IOException {
        long skipped = stream.skip(skip);
        position += skipped;
        while (skipped < skip) {
            byte[] skipBuffer = new byte[1024];
            int read = stream.read(skipBuffer, 0, skipBuffer.length);
            if (read == -1) {
                break;
            } else {
                position += read;
                skipped += read;
            }
        }
    }

    @Override
    public int read(byte[] bytes) throws SevenZipException {
        int read;
        try {
            read = stream.read(bytes);
            position += read;
            return read;
        } catch (IOException e) {
            throw new SevenZipException(e);
        }
    }

    private InputStream openStreamAndCheckSignature(AbstractFile file) throws IOException {
        byte[] buf = new byte[signature.length];

        InputStream iStream;

        int read = 0;
        if (file.isFileOperationSupported(FileOperation.RANDOM_READ_FILE)) {
            RandomAccessInputStream raiStream = file.getRandomAccessInputStream();
            raiStream.seek(0);
            read = StreamUtils.readUpTo(raiStream, buf);
            raiStream.seek(0);
            iStream = raiStream;
        } else {
            PushbackInputStream pushbackInputStream = null;
            try {
                pushbackInputStream = file.getPushBackInputStream(buf.length);
                iStream = pushbackInputStream;
                read = StreamUtils.readUpTo(pushbackInputStream, buf);
                // TODO sometimes reading from pushbackInputStream returns 0
                if (read <= 0 && file.getSize() > 0) {
                    return file.getInputStream();
                }
                pushbackInputStream.unread(buf, 0, read);
            } catch (IllegalArgumentException e) {
                return file.getInputStream();
            }
        }

        if (!checkSignature(buf)) {
            iStream.close();
            throw new IOException("Wrong file signature was " + StrUtils.bytesToHexStr(buf, 0, read)
                    + " but should be " + StrUtils.bytesToHexStr(signature, 0, signature.length));
        }
        return iStream;
    }

    private boolean checkSignature(byte[] data) {
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        if (stream != null) {
            stream.close();
        }
    }
}
