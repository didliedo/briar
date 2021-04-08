package org.briarproject.briar.android.socialbackup.recover;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.contact.add.nearby.CameraException;
import org.briarproject.briar.android.contact.add.nearby.CameraView;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.view.QrCodeView;

import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.ViewModelProvider;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.HORIZONTAL;
import static android.widget.Toast.LENGTH_LONG;
import static java.util.logging.Level.WARNING;
import static org.briarproject.bramble.util.LogUtils.logException;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ReturnShardFragment extends BaseFragment
		implements QrCodeView.FullscreenListener {

	public static final String TAG = org.briarproject.briar.android.contact.add.nearby.AddNearbyContactFragment.class.getName();

	private static final Logger LOG = Logger.getLogger(TAG);

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private ReturnShardViewModel viewModel;
	private CameraView cameraView;
	private LinearLayout cameraOverlay;
	private View statusView;
	private QrCodeView qrCodeView;
	private TextView status;

	public static ReturnShardFragment newInstance() {
		Bundle args = new Bundle();
		ReturnShardFragment fragment = new ReturnShardFragment();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(ReturnShardViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_keyagreement_qr, container,
				false);
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		cameraView = view.findViewById(R.id.camera_view);
		cameraOverlay = view.findViewById(R.id.camera_overlay);
		statusView = view.findViewById(R.id.status_container);
		status = view.findViewById(R.id.connect_status);
		qrCodeView = view.findViewById(R.id.qr_code_view);
		qrCodeView.setFullscreenListener(this);

		viewModel.getState().observe(getViewLifecycleOwner(),
				this::onReturnShardStateChanged);
	}

	@Override
	public void onActivityCreated(@Nullable Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		requireActivity().setRequestedOrientation(SCREEN_ORIENTATION_NOSENSOR);
		cameraView.setPreviewConsumer(viewModel.getQrCodeDecoder());
	}

	@Override
	public void onStart() {
		super.onStart();
		try {
			cameraView.start();
		} catch (CameraException e) {
			logCameraExceptionAndFinish(e);
		}
	}

	@Override
	public void onStop() {
		super.onStop();
		try {
			cameraView.stop();
		} catch (CameraException e) {
			logCameraExceptionAndFinish(e);
		}
	}

	@Override
	public void onDestroy() {
		requireActivity()
				.setRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
		super.onDestroy();
	}

	@Override
	public void setFullscreen(boolean fullscreen) {
		LinearLayout.LayoutParams statusParams, qrCodeParams;
		if (fullscreen) {
			// Grow the QR code view to fill its parent
			statusParams = new LinearLayout.LayoutParams(0, 0, 0f);
			qrCodeParams = new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, 1f);
		} else {
			// Shrink the QR code view to fill half its parent
			if (cameraOverlay.getOrientation() == HORIZONTAL) {
				statusParams = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f);
				qrCodeParams = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f);
			} else {
				statusParams = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
				qrCodeParams = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
			}
		}
		statusView.setLayoutParams(statusParams);
		qrCodeView.setLayoutParams(qrCodeParams);
		cameraOverlay.invalidate();
	}

	@UiThread
	private void onReturnShardStateChanged(@Nullable ReturnShardState state) {
		if (state instanceof ReturnShardState.KeyAgreementListening) {
			Bitmap qrCode =
					((ReturnShardState.KeyAgreementListening) state).qrCode;
			qrCodeView.setQrCode(qrCode);
		} else if (state instanceof ReturnShardState.QrCodeScanned) {
			try {
				cameraView.stop();
			} catch (CameraException e) {
				logCameraExceptionAndFinish(e);
			}
			cameraView.setVisibility(INVISIBLE);
			statusView.setVisibility(VISIBLE);
			status.setText(R.string.connecting_to_device);
		} else if (state instanceof ReturnShardState.KeyAgreementWaiting) {
			status.setText(R.string.waiting_for_contact_to_scan);
		} else if (state instanceof ReturnShardState.KeyAgreementStarted) {
			qrCodeView.setVisibility(INVISIBLE);
			status.setText(R.string.authenticating_with_device);
		} else if (state instanceof ReturnShardState.SocialBackupExchangeStarted) {
			status.setText(R.string.exchanging_contact_details);
		} else if (state instanceof ReturnShardState.Failed) {
			// the activity will replace this fragment with an error fragment
			statusView.setVisibility(INVISIBLE);
			cameraView.setVisibility(INVISIBLE);
		}
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@UiThread
	private void logCameraExceptionAndFinish(CameraException e) {
		logException(LOG, WARNING, e);
		Toast.makeText(getActivity(), R.string.camera_error,
				LENGTH_LONG).show();
		finish();
	}

	@Override
	protected void finish() {
		requireActivity().getSupportFragmentManager().popBackStack();
	}

}
