package com.sf2studio.sf2;

import java.io.IOException;

/** Random-access read source (a file, usually). Sample data is read lazily from it. */
public interface Source {
    long length();

    void readFully(long pos, byte[] b, int off, int len) throws IOException;
}
