package org.briarproject.briar.android.mailbox;

import android.content.Context;

import org.briarproject.briar.R;

import java.util.Map;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.util.Consumer;
import androidx.fragment.app.FragmentActivity;

import static android.Manifest.permission.CAMERA;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale;
import static androidx.core.content.ContextCompat.checkSelfPermission;
import static org.briarproject.briar.android.util.UiUtils.getGoToSettingsListener;

public class CameraPermissionManager {

	private enum Permission {
		UNKNOWN, GRANTED, SHOW_RATIONALE, PERMANENTLY_DENIED
	}

	private Permission cameraPermission = Permission.UNKNOWN;

	private final FragmentActivity ctx;
	private final Consumer<String[]> requestPermissions;

	CameraPermissionManager(FragmentActivity ctx,
			Consumer<String[]> requestPermissions) {
		this.ctx = ctx;
		this.requestPermissions = requestPermissions;
	}

	void resetPermissions() {
		cameraPermission = Permission.UNKNOWN;
	}

	static boolean areEssentialPermissionsGranted(Context ctx) {
		return checkSelfPermission(ctx, CAMERA) == PERMISSION_GRANTED;
	}

	private boolean areEssentialPermissionsGranted() {
		return cameraPermission == Permission.GRANTED;
	}

	boolean checkPermissions() {
		if (areEssentialPermissionsGranted()) return true;
		// If an essential permission has been permanently denied, ask the
		// user to change the setting
		if (cameraPermission == Permission.PERMANENTLY_DENIED) {
			showDenialDialog(ctx, R.string.permission_camera_title,
					R.string.permission_camera_qr_denied_body);
		} else if (cameraPermission == Permission.SHOW_RATIONALE) {
			showRationale(ctx, R.string.permission_camera_title,
					R.string.permission_camera_request_body,
					this::requestPermissions);
		} else {
			requestPermissions();
		}
		return false;
	}

	public static void showDenialDialog(FragmentActivity ctx,
			@StringRes int title, @StringRes int body) {
		AlertDialog.Builder builder =
				new AlertDialog.Builder(ctx, R.style.BriarDialogTheme);
		builder.setTitle(title);
		builder.setMessage(body);
		builder.setPositiveButton(R.string.ok, getGoToSettingsListener(ctx));
		builder.setNegativeButton(R.string.cancel,
				(dialog, which) -> ctx.supportFinishAfterTransition());
		builder.show();
	}

	public static void showRationale(FragmentActivity ctx, @StringRes int title,
			@StringRes int body, Runnable requestPermissions) {
		AlertDialog.Builder builder =
				new AlertDialog.Builder(ctx, R.style.BriarDialogTheme);
		builder.setTitle(title);
		builder.setMessage(body);
		builder.setNeutralButton(R.string.continue_button,
				(dialog, which) -> requestPermissions.run());
		builder.show();
	}

	private void requestPermissions() {
		String[] permissions = new String[] {CAMERA};
		requestPermissions.accept(permissions);
	}

	void onRequestPermissionResult(Map<String, Boolean> result) {
		if (gotPermission(result)) {
			cameraPermission = Permission.GRANTED;
		} else if (shouldShowRequestPermissionRationale(ctx, CAMERA)) {
			cameraPermission = Permission.SHOW_RATIONALE;
		} else {
			cameraPermission = Permission.PERMANENTLY_DENIED;
		}
	}

	private boolean gotPermission(Map<String, Boolean> result) {
		Boolean permissionResult = result.get(CAMERA);
		return permissionResult == null ? areEssentialPermissionsGranted(ctx) :
				permissionResult;
	}

}
