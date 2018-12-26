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

import android.annotation.SuppressLint;
import android.content.Intent;
import android.helpers.Util;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGImageView;
import com.caverock.androidsvg.SVGParseException;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

public class GnuplotGraphActivity extends AppCompatActivity {
	private ZoomControls zoomControls;

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.gnuplot_graph_activity);
		Util.dimUIWith(findViewById(R.id.gnuplot_graph_view));

		final Intent origIntent = getIntent();
		final String graphFile = origIntent.getStringExtra("graph");

		final SVGImageView graphView = (SVGImageView) findViewById(R.id.gnuplot_graph_view);

		try {
			final SVG img = SVG.getFromInputStream(new BufferedInputStream(new FileInputStream(graphFile)));
			graphView.setSVG(img);
			zoomControls = new ZoomControls(this, graphView, img.getDocumentWidth(), img.getDocumentHeight());
		} catch (FileNotFoundException e) {
			Log.d("MoA", "Graph file was not found: " + e);
			Toast.makeText(this, "Graph file " + graphFile + " is misssing!", Toast.LENGTH_LONG).show();
		} catch (SVGParseException e) {
			Log.d("MoA", "Graph file contains invalid svg: " + e);
			Toast.makeText(this, "Graph file " + graphFile + " contains invalid svg!", Toast.LENGTH_LONG).show();
		}
	}
}
