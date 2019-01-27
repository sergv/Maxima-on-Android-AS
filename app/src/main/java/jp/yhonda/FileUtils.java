package jp.yhonda;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

public class FileUtils {

    public static boolean exists(final String path) {
		return exists(new File(path));
    }

    public static boolean exists(final File path) {
        return path.exists();
    }

    public static String readFile(final File src) throws IOException {
        final StringBuilder text = new StringBuilder();
        final BufferedReader br  = new BufferedReader(new FileReader(src));

        try {
            String line;
            if ((line = br.readLine()) != null) {
                text.append(line);
                while ((line = br.readLine()) != null) {
                    text.append('\n');
                    text.append(line);
                }
            }
        } finally {
            br.close();
        }

        return text.toString();
    }

    public static void deleteRecursive(final File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (final File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    public static void copyFile(final File src, final File dest)
            throws IOException {
        final BufferedInputStream fileInputStream =
                new BufferedInputStream(new FileInputStream(src));
        final BufferedOutputStream out =
                new BufferedOutputStream(new FileOutputStream(dest));
        int read;
        final byte[] buffer = new byte[1024];
        while ((read = fileInputStream.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        out.close();
        fileInputStream.close();
    }


}
