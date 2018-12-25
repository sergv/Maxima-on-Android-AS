/*
    Copyright 2012, 2013, Yasuaki Honda (yasuaki.honda@gmail.com)
    This file is part of MaximaOnAndroid.

    MaximaOnAndroid is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    MaximaOnAndroid is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with MaximaOnAndroid.  If not, see <http://www.gnu.org/licenses/>.
 */
package jp.yhonda;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.io.IOException;
import java.io.OutputStream;

public class CommandExec {
	private final StringBuilder sb = new StringBuilder(); // output buffer
	private final ProcessBuilder builder;
	private final Process proc;
	private final InputStream is;
	private final long pid;

	public CommandExec (final List<String> commandList) throws IOException {
		builder = new ProcessBuilder(commandList);
		// process starts
		proc = builder.start();
		pid = processPID(proc);
		is = proc.getInputStream();
		while (true) {
			final int c = is.read();
			switch (c) {
				case -1:
					is.close();
					return;
				case 0x04:
					return;
				default:
					sb.append((char) c);
					break;
			}
		}
	}

	public void maximaCmd(final String mcmd) throws IOException {
		if (!mcmd.trim().isEmpty()) {
			// obtain process standard output stream
			final OutputStream os = proc.getOutputStream();
			os.write(mcmd.getBytes("UTF-8"));
			os.flush();
		}
		while (true) {
			final int c = is.read();
			switch (c) {
			case 0x04:
				/* 0x04 is the prompt indicator */
				/*
				 * if (is.available()==0) { break; }
				 */
				return;
			case -1:
				is.close();
				return;
			case 0x5c:
				// 0x5c needs to be escaped by 0x5c, the backslash.
				sb.append((char) c);
				sb.append((char) c);
				break;
			case 0x27:
				// 0x27 needs to be escaped as it is ' - single quote.
				sb.append((char) 0x5c);
				sb.append((char) c);
				break;
			default:
				sb.append((char) c);
			}
		}
	}

	public String getProcessResult() {
		return (new String(sb));
	}

	public void clearStringBuilder() {
		sb.delete(0, sb.length());
	}

	public long getPID() {
		return pid;
	}

	private static long processPID(final Process proc) {
		long pid = -1;
		try {
			final Field f = proc.getClass().getDeclaredField("pid");
			f.setAccessible(true);
			pid = f.getLong(proc);
			f.setAccessible(false);
		} catch (Exception e) {
			pid = -1;
		}
		if (pid != -1) {
			return pid;
		}
		try {
			final Field f = proc.getClass().getDeclaredField("id");
			f.setAccessible(true);
			pid = f.getLong(proc);
			f.setAccessible(false);
		} catch (Exception e) {
			pid = -1;
		}
		return pid;
	}
}
