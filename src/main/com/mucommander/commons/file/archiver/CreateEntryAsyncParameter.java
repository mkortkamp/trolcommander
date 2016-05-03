package com.mucommander.commons.file.archiver;

import java.io.IOException;
import java.io.OutputStream;
import com.mucommander.commons.file.FileAttributes;
import com.mucommander.utils.ThrowingConsumer;

public class CreateEntryAsyncParameter {
    private String entryPath;
    private FileAttributes attributes;
    private Runnable beforeProcessing;
    private ThrowingConsumer<OutputStream, IOException> entryContentWriter;

    public CreateEntryAsyncParameter(String entryPath, FileAttributes attributes, Runnable beforeProcessing,
            ThrowingConsumer<OutputStream, IOException> entryContentWriter) {
        this.entryPath = entryPath;
        this.attributes = attributes;
        this.beforeProcessing = beforeProcessing;
        this.entryContentWriter = entryContentWriter;
    }
    public String getEntryPath() {
        return entryPath;
    }
    public void setEntryPath(String entryPath) {
        this.entryPath = entryPath;
    }
    public FileAttributes getAttributes() {
        return attributes;
    }
    public void setAttributes(FileAttributes attributes) {
        this.attributes = attributes;
    }
    public Runnable getBeforeProcessing() {
        return beforeProcessing;
    }
    public void setBeforeProcessing(Runnable beforeProcessing) {
        this.beforeProcessing = beforeProcessing;
    }
    public ThrowingConsumer<OutputStream, IOException> getEntryContentWriter() {
        return entryContentWriter;
    }
    public void setEntryContentWriter(ThrowingConsumer<OutputStream, IOException> entryContentWriter) {
        this.entryContentWriter = entryContentWriter;
    }
}