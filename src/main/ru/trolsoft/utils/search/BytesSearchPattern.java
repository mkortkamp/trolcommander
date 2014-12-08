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
package ru.trolsoft.utils.search;

/**
 * @author Oleg Trifonov
 * Created on 08/12/14.
 */
public class BytesSearchPattern implements SearchPattern {
    private final byte[] bytes;

    public BytesSearchPattern(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public int length() {
        return bytes.length;
    }

    @Override
    public boolean checkByte(int index, int val) {
        return (bytes[index] & 0xff) == val;
    }

    @Override
    public boolean checkSelf(int index1, int index2) {
        return bytes[index1] == bytes[index2];
    }
}
