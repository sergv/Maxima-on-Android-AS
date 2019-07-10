package jp.yhonda;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.*;
import android.content.SharedPreferences;
import android.content.SharedPreferences.*;
import android.preference.PreferenceManager;
import java.util.*;

/*
    Copyright 2012, 2013, 2014, 2015, 2016, 2017 Yasuaki Honda (yasuaki.honda@gmail.com)
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

public final class MoAPreferenceActivity extends PreferenceActivity implements OnSharedPreferenceChangeListener {
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preference);
    }

    @Override
    protected void onResume() {
        super.onResume();
        final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPrefs.registerOnSharedPreferenceChangeListener(this);

        final int list[] = { R.string.input_auto_completion_pref, R.string.maxima_manual_language_pref, R.string.input_area_font_size_pref, R.string.browser_font_size_pref };
        for (final int resId : list) {
            AppGlobals.getSingleton().set(getString(resId), "false");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        updatePreferenceSummary(key);
        AppGlobals.getSingleton().set(key, "true");
    }

    private void updatePreferenceSummary(final String key) {
        final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        final Preference pref = findPreference(key);

        if (pref instanceof CheckBoxPreference) {
            final CheckBoxPreference x = (CheckBoxPreference)pref;
            if (x.isChecked()) {
                x.setSummary("Yes");
            } else {
                x.setSummary("No");
            }
        } else if (pref instanceof ListPreference) {
            final ListPreference x = (ListPreference)pref;
            x.setSummary(x.getEntry());
        }

    }
}
