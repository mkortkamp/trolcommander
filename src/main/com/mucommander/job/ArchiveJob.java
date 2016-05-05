/*
 * This file is part of muCommander, http://www.mucommander.com
 * Copyright (C) 2002-2012 Maxence Bernard
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


package com.mucommander.job;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.mucommander.job.utils.ScanDirectoryThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mucommander.commons.file.AbstractFile;
import com.mucommander.commons.file.archiver.Archiver;
import com.mucommander.commons.file.archiver.CreateEntryAsyncParameter;
import com.mucommander.commons.file.util.FileSet;
import com.mucommander.commons.io.StreamUtils;
import com.mucommander.text.Translator;
import com.mucommander.ui.dialog.file.FileCollisionDialog;
import com.mucommander.ui.dialog.file.ProgressDialog;
import com.mucommander.ui.main.MainFrame;


/**
 * This FileJob is responsible for compressing a set of files into an archive file.
 *
 * @author Maxence Bernard
 */
public class ArchiveJob extends TransferFileJob {
	private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveJob.class);
	
    public static TransferFileJob createArchiveJob(ProgressDialog progressDialog, MainFrame mainFrame, FileSet files,
            AbstractFile destFile, int archiveFormat, String archiveComment) {
        return new ArchiveJob(progressDialog, mainFrame, files, destFile, archiveFormat, archiveComment);
    }


    /** Destination archive file */
    private AbstractFile destFile;

    /** Base destination folder's path */
    private String baseFolderPath;

    /** Archiver instance that does the actual archiving */
    private Archiver archiver;

    /** Archive format */
    private int archiveFormat;
	
    /** Optional archive comment */
    private String archiveComment;
	
    protected final ScanDirectoryThread scanDirectoryThread;

    /** Processed files counter */
    protected long processedFilesCount;

    private Optional<Supplier<Float>> asyncTotalPercentDoneSupplier = Optional.empty();



    private ArchiveJob(ProgressDialog progressDialog, MainFrame mainFrame, FileSet files, AbstractFile destFile, int archiveFormat, String archiveComment) {
        super(progressDialog, mainFrame, files);
		
        this.destFile = destFile;
        this.archiveFormat = archiveFormat;
        this.archiveComment = archiveComment;

        this.baseFolderPath = getBaseSourceFolder().getAbsolutePath(false);

        scanDirectoryThread = new ScanDirectoryThread(files);
        scanDirectoryThread.start();
    }


    ////////////////////////////////////
    // TransferFileJob implementation //
    ////////////////////////////////////

    @Override
    protected boolean processFile(AbstractFile file, Object recurseParams) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void startAsyncFileProcessing() {
        try {
            archiver.startAsyncEntriesCreation();
        } catch (IOException e) {
            LOGGER.debug("failed archiving files(s)", e);
            if (getState() != State.INTERRUPTED) {
                showErrorDialog(Translator.get("pack_dialog.error_title"), Translator.get("generic_error"),
                        new String[] { OK_TEXT }, new int[] { OK_ACTION });
            }
        }
    }

    @Override
    protected void processFileAsync(AbstractFile file, Object recurseParams, Supplier<Boolean> beforeProcessing,
            final Consumer<Boolean> onProcessingDone) {
        if (getState() == State.INTERRUPTED) {
            onProcessingDone.accept(false);
            return;
        }

        doProcessFileAsync(file, beforeProcessing, onProcessingDone);
    }

    private void doProcessFileAsync(AbstractFile file, Supplier<Boolean> beforeProcessing,
            final Consumer<Boolean> onProcessingDone) {
        String filePath = file.getAbsolutePath(false);
        String entryRelativePath = filePath.substring(baseFolderPath.length()+1, filePath.length());

        Consumer<Optional<Exception>> errorHandler = oe -> {
            oe.ifPresent(e -> {
                if (getState() != State.INTERRUPTED) {
                    e.printStackTrace();
                }
            });
            if (oe.isPresent()) {
                // TODO missing async error handling - this only works for synchronious operations
                int ret = showErrorDialog(Translator.get("pack_dialog.error_title"),
                        Translator.get("error_while_transferring", file.getAbsolutePath()));
                if (ret == RETRY_ACTION) {
                    // Reset processed bytes currentFileByteCounter
                    getCurrentFileByteCounter().reset();

                    doProcessFileAsync(file, beforeProcessing, onProcessingDone);
                }
                // Cancel, skip or close dialog return false
                onProcessingDone.accept(false);
            }
        };

        if (file.isDirectory() && !file.isSymlink()) {
            // create new directory entry in archive file
            archiver.createEntryAsync(entryRelativePath, file, beforeProcessing, null, errorHandler);

            // Recurse on files
            AbstractFile subFiles[];
            try {
                subFiles = file.ls();
            } catch (IOException e) {
                errorHandler.accept(Optional.of(e));
                return;
            }
            final AtomicBoolean folderComplete = new AtomicBoolean(true);
            for (int i = 0; i < subFiles.length; i++) {
                final boolean lastSubFile = i == subFiles.length - 1;
                final int subFileIndex = i;
                processFileAsync(subFiles[i], null, () -> {// beforeProcessing
                    // Notify job that we're starting to process this file (needed for recursive calls to processFile)
                    nextFile(subFiles[subFileIndex]);
                    return getState() != State.INTERRUPTED;
                }, success -> { // afterProcessing
                    folderComplete.compareAndSet(true, success);
                    if (lastSubFile) {
                        onProcessingDone.accept(folderComplete.get());
                    }
                });
                if (getState() == State.INTERRUPTED && !lastSubFile) {// last file already calls this async
                    onProcessingDone.accept(false);
                    break;
                }
            }
            return;
        } else {
            archiver.createEntryAsync(entryRelativePath, file, beforeProcessing,
                    () -> setCurrentInputStream(file.getInputStream()), errorHandler);
            return;
        }
    }

    @Override
    protected boolean hasFolderChanged(AbstractFile folder) {
        // This job modifies the folder where the archive is
        return folder.equalsCanonical(destFile.getParent());     // Note: parent may be null
    }


    ////////////////////////
    // Overridden methods //
    ////////////////////////

    /**
     * Overriden method to initialize the archiver and handle the case where the destination file already exists.
     */
    @Override
    protected void jobStarted() {
        super.jobStarted();

        // Check for file collisions, i.e. if the file already exists in the destination
        int collision = FileCollisionChecker.checkForCollision(null, destFile);
        if (collision!=FileCollisionChecker.NO_COLLOSION) {
            // File already exists in destination, ask the user what to do (cancel, overwrite,...) but
            // do not offer the multiple files mode options such as 'skip' and 'apply to all'.
            int choice = waitForUserResponse(new FileCollisionDialog(getProgressDialog(), getMainFrame(), collision, null, destFile, false, false));

            // Overwrite file
            if (choice == FileCollisionDialog.OVERWRITE_ACTION) {
                // Do nothing, simply continue and file will be overwritten
            } else {
                // 'Cancel' or close dialog interrupts the job
                interrupt();
                return;
            }
        }

        // Loop for retry
        do {
            try {
                // Tries to get an Archiver instance.
                this.archiver = Archiver.getArchiver(destFile, archiveFormat);
                this.archiver.setComment(archiveComment);

                break;
            } catch (Exception e) {
                int choice = showErrorDialog(Translator.get("pack_dialog.error_title"),
                                             Translator.get("cannot_write_file", destFile.getName()),
                                             new String[] {CANCEL_TEXT, RETRY_TEXT},
                                             new int[]  {CANCEL_ACTION, RETRY_ACTION}
                                             );

                // Retry loops
                if (choice == RETRY_ACTION) {
                    continue;
                }

                // 'Cancel' or close dialog interrupts the job
                interrupt();
                return;
            }
        } while(true);
    }

    /**
     * Overriden method to close the archiver.
     */
    @Override
    public void jobStopped() {

        // TransferFileJob.jobStopped() closes the current InputStream, this will cause copyStream() to return
        super.jobStopped();

        // Try to close the archiver which in turns closes the archive OutputStream and underlying file OutputStream
        if (archiver != null) {
            try {
                archiver.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public String getStatusString() {
        return Translator.get("pack_dialog.packing_file", getCurrentFilename());
    }


    @Override
    public void interrupt() {
        if (scanDirectoryThread != null) {
            scanDirectoryThread.interrupt();
        }
        super.interrupt();
    }


    @Override
    public float getTotalPercentDone() {
        if (asyncTotalPercentDoneSupplier.isPresent()) {
            Float percentDone = asyncTotalPercentDoneSupplier.get().get();
            if (percentDone >= 0) {
                return percentDone;
            }
        }
        if (scanDirectoryThread == null || !scanDirectoryThread.isCompleted()) {
            float result = super.getTotalPercentDone();
            return result > 5 ? 5 : result;
        }
        float progressBySize = 1.0f*(getTotalByteCounter().getByteCount() + getTotalSkippedByteCounter().getByteCount()) / scanDirectoryThread.getTotalBytes();
        float progressByCount = 1.0f*(processedFilesCount-1) / scanDirectoryThread.getFilesCount();
        float result = (progressBySize * 8 + progressByCount * 2) / 10;
        if (result < 0) {
            result = 0;
        } else if (result > 1) {
            result = 1;
        }
        return result;
    }
}
