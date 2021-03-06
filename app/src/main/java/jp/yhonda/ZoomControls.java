package jp.yhonda;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

public class ZoomControls implements View.OnTouchListener {
	private final ImageView controlledView;

	private final int imageWidth, imageHeight;

	private Matrix imageTransform;
	private final Matrix initialFullscreenTransform;
	private Matrix intermediateTransform;

	private Integer finger1 = null;
	private Integer finger2 = null;
	private double intermedX, intermedY;
	private double initialDX, initialDY;

	private float startX, startY;
	private long oldT;

	private static Matrix translationTransform(final double dx, final double dy) {
		final Matrix m = identityMatrix();
		m.setTranslate((float) dx, (float) dy);
		return m;
	}

	private Matrix scaleTransform(final double sx, final double sy) {
		return scaleTransform(
			sx,
			sy,
			controlledView.getWidth()  / 2,
			controlledView.getHeight() / 2);
	}

	private static Matrix scaleTransform(final double sx, final double sy, final double px, final double py) {
		final Matrix m = identityMatrix();
		m.setScale((float) sx, (float) sy, (float) px, (float) py);
		return m;
	}

	private static Matrix identityMatrix() {
		return new Matrix();
	}

	private static Matrix mm(final Matrix m1, final Matrix m2) {
		final Matrix res = new Matrix(m1);
		res.postConcat(m2);
		return res;
	}

	private void updateImage() {
		final Matrix m = mm(imageTransform, intermediateTransform);
		controlledView.setImageMatrix(m);
	}

	private static Matrix fitToScreenTransform(final boolean unifyScales, final int screenWidth, final int screenHeight, final int imageWidth, final int imageHeight) {
		final double scaleX = screenWidth  / imageWidth;
		final double scaleY = screenHeight / imageHeight;

		final double effectiveScaleX = unifyScales ? Math.min(scaleX, scaleY) : scaleX;
		final double effectiveScaleY = unifyScales ? Math.min(scaleX, scaleY) : scaleY;

		return mm(
				translationTransform(screenWidth / 2 - imageWidth / 2, screenHeight / 2 - imageHeight / 2),
				scaleTransform((float) effectiveScaleX, (float) effectiveScaleY, screenWidth / 2, screenHeight / 2));
	}

	public ZoomControls(final Context ctx, final ImageView controlledView, final int imageWidth, final int imageHeight) {
		this.controlledView = controlledView;
		this.imageWidth     = imageWidth;
		this.imageHeight    = imageHeight;

		final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
		final Display display = wm.getDefaultDisplay();
		final Point size = new Point();
		display.getSize(size);
		final int width  = size.x;
		final int height = size.y - 128; // subtract 128 as a heuristic to account for title bar that takes screen space

		controlledView.setScaleType(ImageView.ScaleType.MATRIX);

		initialFullscreenTransform = fitToScreenTransform(true, width, height, imageWidth, imageHeight);

		imageTransform = initialFullscreenTransform;
		intermediateTransform = identityMatrix();

		updateImage();

		controlledView.setOnTouchListener(this);
	}

	@Override
	public boolean onTouch(final View view, final MotionEvent event) {

		final int action = event.getActionMasked();
		boolean ret = false;

		//LogUtils.d("MoA", "onTouch: event.getPointerCount() = " + event.getPointerCount());
		switch (event.getPointerCount()) {
		case 1:
			switch (action) {
			case MotionEvent.ACTION_DOWN: {
				final long newT = System.currentTimeMillis();
				if (Math.abs(newT - oldT) < 350) {
					// float-tap
					onDoubleTap();
				} else {
					finger1 = event.getPointerId(0);
					finger2 = null;
					final int idx1 = event.findPointerIndex(finger1);
					if (idx1 == -1) {
						return false;
					}
					//LogUtils.d("MoA", "onTouch: 1/MotionEvent.ACTION_DOWN");
					intermedX = startX = event.getX(idx1);
					intermedY = startY = event.getY(idx1);
				}
				oldT = newT;
				ret = true;
				break;
			}
			case MotionEvent.ACTION_MOVE: {
				if (finger1 == null) {
					return false;
				}
				final int idx1 = event.findPointerIndex(finger1);
				if (idx1 == -1) {
					return false;
				}
				//LogUtils.d("MoA", "onTouch: 1/MotionEvent.ACTION_MOVE");
				intermedX = event.getX(idx1);
				intermedY = event.getY(idx1);
				final double dx = intermedX - startX;
				final double dy = intermedY - startY;
				if (Math.abs(dx) >= 1 || Math.abs(dy) >= 1) {
					onFlingInProgress(dx, dy);
					ret = true;
				} else {
					ret = false;
				}
				break;
			}
			case MotionEvent.ACTION_UP: {
				if (finger1 == null) {
					return false;
				}
				final int idx1 = event.findPointerIndex(finger1);
				if (idx1 == -1) {
					return false;
				}
				//LogUtils.d("MoA", "onTouch: 1/MotionEvent.ACTION_UP");
				intermedX = event.getX(idx1);
				intermedY = event.getY(idx1);
				final double dx = intermedX - startX;
				final double dy = intermedY - startY;
				if (Math.abs(dx) >= 1 || Math.abs(dy) >= 1) {
					onFlingFinished(dx, dy);
					finger1 = finger2 = null;
					ret = true;
				} else {
					ret = false;
				}
				break;
			}
			default:
			}
			break;
		case 2:
			switch (action) {
			case MotionEvent.ACTION_DOWN: {
				finger1 = event.getPointerId(0);
				final int idx1 = event.findPointerIndex(finger1);
				if (idx1 == -1) {
					return false;
				}
				//LogUtils.d("MoA", "onTouch: 2/MotionEvent.ACTION_DOWN");
				intermedX = event.getX(idx1);
				intermedY = event.getY(idx1);
				ret = true;
				break;
			}
			case MotionEvent.ACTION_POINTER_DOWN: {
				final int idx2 = event.getActionIndex();
				if (idx2 == -1) {
					return false;
				}
				//LogUtils.d("MoA", "onTouch: 2/MotionEvent.ACTION_POINTER_DOWN");
				finger2 = event.getPointerId(idx2);
				final float x2 = event.getX(idx2);
				final float y2 = event.getY(idx2);
				initialDX = x2 - intermedX;
				initialDY = y2 - intermedY;
				ret = true;
				break;
			}
			case MotionEvent.ACTION_MOVE:

			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_POINTER_UP:
			case MotionEvent.ACTION_CANCEL: {
				if (finger1 == null || finger2 == null) {
					return false;
				}
				final int idx1 = event.findPointerIndex(finger1);
				final int idx2 = event.findPointerIndex(finger2);
				if (idx1 == -1 || idx2 == -1) {
					return false;
				}
				LogUtils.d("onTouch: 2/MotionEvent.ACTION_*");
				final float x1 = event.getX(idx1);
				final float y1 = event.getY(idx1);
				final float x2 = event.getX(idx2);
				final float y2 = event.getY(idx2);
				final float dx = x2 - x1;
				final float dy = y2 - y1;
				final double sx = dx / initialDX;
				final double sy = dy / initialDY;
				if (action == MotionEvent.ACTION_MOVE) {
					onZoomInProgress(sx, sy);
				} else {
					onZoomFinished(sx, sy);
					// Make it so that any leftover touches for 1 finger
					// will be discarded.
					finger1 = finger2 = null;
				}
				ret = true;
				break;
			}
			default:
			}
			break;
		default:
		}
		return ret;
	}

	private void onDoubleTap() {
		LogUtils.d("onDoubleTap");
		imageTransform = initialFullscreenTransform;
		updateImage();
	}

	private void onFlingInProgress(final double dx, final double dy) {
		//LogUtils.d("MoA", "onFlingInProgress: dx = " + dx + ", dy = " + dy);
		intermediateTransform = translationTransform(dx, dy);
		updateImage();
	}

	private void onFlingFinished(final double dx, final double dy) {
		//LogUtils.d("MoA", "onFlingFinished: dx = " + dx + ", dy = " + dy);
		imageTransform = mm(imageTransform, intermediateTransform);
		// imageTransform = mm(imageTransform, translationTransform(dx, dy));
		intermediateTransform = identityMatrix();
		updateImage();
	}

	private void onZoomInProgress(final double sx, final double sy) {
		//LogUtils.d("MoA", "onZoomInProgress: sx = " + sx + ", sy = " + sy);
		intermediateTransform = scaleTransform(sx, sy);
		updateImage();
	}

	private void onZoomFinished(final double sx, final double sy) {
		//LogUtils.d("MoA", "onZoomFinished: sx = " + sx + ", sy = " + sy);
		// imageTransform = mm(imageTransform, scaleTransform(sx, sy));
		imageTransform = mm(imageTransform, intermediateTransform);
		intermediateTransform = identityMatrix();
		updateImage();
	}

	public void zoom(final double s) {
		imageTransform = mm(imageTransform, scaleTransform(s, s));
		updateImage();
	}

	public void resetZoomAndPosition() {
		imageTransform = initialFullscreenTransform;
		updateImage();
	}

	public void fitToScreen() {
		final int width = controlledView.getWidth();
		final int height = controlledView.getHeight();
		if (width != 0 && height != 0) {
			imageTransform = fitToScreenTransform(false, width, height, imageWidth, imageHeight);
			updateImage();
		} else {
			resetZoomAndPosition();
		}
	}

}
