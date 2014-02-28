package de.otto.jobstore.service;

import de.otto.jobstore.common.RemoteJob;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.zip.GZIPOutputStream;

/**
 * The script archiver generates a tar with all needed artefacts (scripts, config files...)
 * <p/>
 * It is passed the directory where all jobs reside. Every job has a matching subdirectory in the jobs folder.
 * <p/>
 * It is also passed the job name with an optional environment postfix. Example: jobname_develop
 * <p/>
 * The archiver puts all the "global" files of the specific job folder into the tar.
 * If the job name has an environment prefix the archiver searches for a subfolder with the name of the postfix and
 * adds all the files into the jar.
 * <p/>
 * Example:
 * <p/>
 * /jobs
 * /jobname1
 * /development
 * dev.config
 * /live
 * live.config
 * aScript.sh
 * /jobname2
 * <p/>
 * If you pass "jobs" as directory and "jobname1_development" as parameters then the generated tar contains the following
 * files:
 * - aScript.sh
 * - dev.config
 */
public class DirectoryBasedScriptArchiver implements ScriptArchiver {

    private static final int FOR_ALL_EXECUTABLE_FILE = 0100755;

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectoryBasedScriptArchiver.class);
    private String directory;

    public DirectoryBasedScriptArchiver(String directory) {
        this.directory = directory;
    }

    @Override
    public InputStream createArchive(RemoteJob remoteJob) throws IOException, URISyntaxException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        try (TarArchiveOutputStream tarArchive = new TarArchiveOutputStream(
                new GZIPOutputStream(
                        new BufferedOutputStream(byteArrayOutputStream)))) {

            String jobName = remoteJob.name;
            writeEntriesForDirectory(getJobDirectoryNameWithoutEnvironment(directory, jobName), tarArchive);
            if (hasEnvironmentPostfix(jobName)) {
                writeEntriesForDirectory(getEnvironmentSubDirectory(directory, jobName), tarArchive);
            }
        }

        return new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
    }

    private boolean hasEnvironmentPostfix(String jobName) {
        return jobName.indexOf("_") > -1;
    }

    private String getJobDirectoryNameWithoutEnvironment(String directory, String jobNameWithEnvironmentPostfix) {
        String subFolder;
        if (hasEnvironmentPostfix(jobNameWithEnvironmentPostfix)) {
            int indexOfSeparator = jobNameWithEnvironmentPostfix.lastIndexOf("_");
            subFolder = jobNameWithEnvironmentPostfix.substring(0, indexOfSeparator);
        } else {
            subFolder = jobNameWithEnvironmentPostfix;
        }
        return directory + File.separator + subFolder;
    }

    private String getEnvironmentSubDirectory(String directory, String jobNameWithEnvironmentPostfix) {
        int indexOfSeparator = jobNameWithEnvironmentPostfix.lastIndexOf("_");
        return getJobDirectoryNameWithoutEnvironment(directory, jobNameWithEnvironmentPostfix)
                + File.separator
                + jobNameWithEnvironmentPostfix.substring(indexOfSeparator + 1);
    }

    private void writeEntriesForDirectory(String directoryWithoutEnvPostfix, TarArchiveOutputStream tarArchive) throws URISyntaxException, IOException {
        for (File file : getResources(directoryWithoutEnvPostfix)) {
            if (file.isFile()) {
                writeEntry(tarArchive, file);
            }
        }
    }

    private void writeEntry(TarArchiveOutputStream tarArchive, File file) throws IOException {
        try (InputStream fis = new FileInputStream(file)) {
            TarArchiveEntry tarArchiveEntry = new TarArchiveEntry(file.getName());
            tarArchiveEntry.setSize(file.length());
            tarArchiveEntry.setMode(FOR_ALL_EXECUTABLE_FILE);
            tarArchive.putArchiveEntry(tarArchiveEntry);
            IOUtils.copy(fis, tarArchive);
            tarArchive.closeArchiveEntry();
        }
    }

    private File[] getResources(String path) throws URISyntaxException, IOException {
        File[] result;
        URL dirURL = getClass().getResource(path);
        if (dirURL == null) {
            LOGGER.info("Could not find directory \"" + path + "\"");
            result = new File[0];
        } else {
            File directory = new File(dirURL.toURI());
            result = directory.listFiles();
        }

        return result;
    }
}
