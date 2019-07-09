/*
    Copyright 2015 Yasuaki Honda (yasuaki.honda@gmail.com)
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
    along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 */

package jp.yhonda;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.util.Log;

import jp.yhonda.CommandExec;

public final class CpuArchitecture {

	public enum Arch {
		x86, arm
	}

	private static Arch cpuarch = null;

	private CpuArchitecture () {
	}

	public static Arch getCpuArchitecture() {
		return cpuarch;
	}

	public static void initCpuArchitecture() {
		final String arch = System.getProperty("os.arch");
		LogUtils.d("arch = " + arch);
		if (arch.equals("x86_64") || arch.equals("x86")) {
			cpuarch = Arch.x86;
		} else if (arch.equals("armeabi-v7a") || arch.equals("armeabi") || arch.equals("arm64-v8a") || arch.equals("armv7l") || arch.equals("aarch64"))  {
			cpuarch = Arch.arm;
		}
	}

	public static String getMaximaExecutableName() {
		final String arch;
		switch (cpuarch) {
			case x86: arch = ".x86"; break;
			case arm: arch = "";     break;
			default:  arch = "";     break;
		}
		// Lollipop requires PIE + newer versions of MoA don't bundle non-PIE
		// executables.
		final String pie = ".pie";
		return "maxima" + arch + pie;
	}
}
