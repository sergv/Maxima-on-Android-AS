package jp.yhonda;

import java.io.File;

public class FileUtils {

    public static void deleteRecursive(final File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (final File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

}