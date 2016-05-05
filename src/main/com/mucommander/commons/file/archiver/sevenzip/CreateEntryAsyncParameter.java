package com.mucommander.commons.file.archiver.sevenzip;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.mucommander.commons.file.FileAttributes;
import com.mucommander.utils.ThrowingSupplier;

public class CreateEntryAsyncParameter {
    private String entryPath;
    private FileAttributes attributes;
    private Supplier<Boolean> beforeProcessing;
    private ThrowingSupplier<InputStream, IOException> entrySourceSupplier;
    private Consumer<Optional<Exception>> onProcessingDone;

    public CreateEntryAsyncParameter(String entryPath, FileAttributes attributes, Supplier<Boolean> beforeProcessing,
            ThrowingSupplier<InputStream, IOException> entrySourceSupplier,
            Consumer<Optional<Exception>> onProcessingDone) {
        this.entryPath = entryPath;
        this.attributes = attributes;
        this.beforeProcessing = beforeProcessing;
        this.setEntrySourceSupplier(entrySourceSupplier);
        this.setOnProcessingDone(onProcessingDone);
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
    public Supplier<Boolean> getBeforeProcessing() {
        return beforeProcessing;
    }
    public void setBeforeProcessing(Supplier<Boolean> beforeProcessing) {
        this.beforeProcessing = beforeProcessing;
    }
    public ThrowingSupplier<InputStream, IOException> getEntrySourceSupplier() {
        return entrySourceSupplier;
    }
    public void setEntrySourceSupplier(ThrowingSupplier<InputStream, IOException> entrySourceSupplier) {
        this.entrySourceSupplier = entrySourceSupplier;
    }
    public Consumer<Optional<Exception>> getOnProcessingDone() {
        return onProcessingDone;
    }
    public void setOnProcessingDone(Consumer<Optional<Exception>> onProcessingDone) {
        this.onProcessingDone = onProcessingDone;
    }
}