package jp.yhonda;

import java.io.File;

public class FileUtils {

    public static boolean exists(final String path) {
		return (new File(path)).exists();
    }

    public static void deleteRecursive(final File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (final File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

}
