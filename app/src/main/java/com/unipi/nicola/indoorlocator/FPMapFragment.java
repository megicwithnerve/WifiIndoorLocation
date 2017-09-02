package com.unipi.nicola.indoorlocator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PointF;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CustomCap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.unipi.nicola.indoorlocator.fingerprinting.WifiFingerprint;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The fragment carrying the google map.
 */

public class FPMapFragment extends Fragment implements OnMapReadyCallback, View.OnClickListener {
    public static final String TAG = "FPMapFragment";

    //messages
    public static final int MSG_STEP = 1;

    private IndoorLocatorApplication app;
    private GoogleMap gMap = null;
    private View rootView;

    /*
     * The messenger object that must be passed from the activity and that is needed in order for this fragment
     * to communicate with the Fingerprinting Service
     */
    private Messenger mInertialNavigationService;
    private Messenger mMessenger = new Messenger(new FPMapFragment.IncomingHandler());

    //the current shown marker
    private Marker currentMarker;
    //the current user path
    private Polyline userPath;
    private TextView calibrationLabel;

    private TextView stepsCounter;
    int steps = 0;

    private Context mContext;

    //set containing all the estimated locations visited so far. It is used in order to print once
    //the marker on the map for every distinct estimated location
    private static class ComparableLocation extends Location{
        public ComparableLocation(Location l) {
            super(l);
        }
        @Override
        public boolean equals(Object o){
            ComparableLocation cl = (ComparableLocation) o;
            return cl.getExtras().getString("id").equals(this.getExtras().getString("id"));
        }
        @Override
        public int hashCode() {
            return this.getExtras().getString("id").hashCode();
        }
    }
    private Set<ComparableLocation> estimatedLocationsSet = new HashSet<>();

    public FPMapFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getContext();
    }

    @Override
    public void setArguments(Bundle b) {
        //get the messenger from the activity
        mInertialNavigationService = b.getParcelable("mInertialNavigationService");

        //sends an hello message so that the service knows who he is talking to
        Utils.sendMessage(mInertialNavigationService, InertialPedestrianNavigationService.MSG_HELLO_FROM_MAP_FRAGMENT, null, mMessenger);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        if(rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_fp_map, container, false);
        }

        //add listener for reset button
        Button reset = (Button)rootView.findViewById(R.id.reset_path);
        reset.setOnClickListener(this);

        //setup the calibration label
        calibrationLabel = (TextView) rootView.findViewById(R.id.calibration_label);
        calibrationLabel.setOnClickListener(this);
        updateCalibration();

        stepsCounter = (TextView) rootView.findViewById(R.id.steps_counter);

        //restore the state of buttons and text views
        if(savedInstanceState != null){
            handleRealPositioningOn();
            steps = savedInstanceState.getInt("steps");
        }

        updateSteps();
        return rootView;
    }

    private void handleRealPositioningOn(){
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getContext());
        boolean realPositioningEnabled = sharedPref.getBoolean("show_real_position", false);

        try {
            //enable or disable map positioning
            if(gMap != null)
                gMap.setMyLocationEnabled(realPositioningEnabled);
        } catch (SecurityException e) {
            Toast.makeText(getContext(), R.string.no_location_permissions, Toast.LENGTH_LONG);
            e.printStackTrace();
        }
    }

    private void updateCalibration(){
        CalibrationData cd = CalibrationUtils.getCalibrationInUse(getContext());
        String currentCalibrationLabel = (cd == null) ? "Default" : cd.getLabel();
        calibrationLabel.setText(currentCalibrationLabel);
    }

    private void updateSteps(){
        if(isAdded()){
            //prevent IllegalStateException calling getString on a potentially detached fragment
            stepsCounter.setText(MessageFormat.format(
                    getString(R.string.steps_count),
                    steps));
        }

    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == WifiLocatorActivity.CALIBRATION_ACTIVITY){
            Log.d(TAG, "Calibration activity finished!");
            updateCalibration();
        }
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        SupportMapFragment mapFragment = (SupportMapFragment)getChildFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
    }

    @Override
    public void onStart(){
        super.onStart();
        //Registers the broadcast receiver to receive notifications about new position estimation available
        getActivity().registerReceiver(locationEstimationAvailable, new IntentFilter(
                IndoorLocatorApplication.LOCATION_ESTIMATION_READY));
        getActivity().registerReceiver(inertialPositionAvailable, new IntentFilter(
                IndoorLocatorApplication.NEW_INERTIAL_POSITION_AVAILABLE));
        app = (IndoorLocatorApplication) getActivity().getApplication();
    }

    @Override
    public void onStop(){
        super.onStop();
        getActivity().unregisterReceiver(locationEstimationAvailable);
        getActivity().unregisterReceiver(inertialPositionAvailable);
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume called!");

        displayMarkers();
        displayPath();
        handleRealPositioningOn();
        updateCalibration();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        /*gMap.clear();
        gMap = null;*/
        /*SupportMapFragment f = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.map);
        if (f != null)
            getFragmentManager().beginTransaction().remove(f).commitAllowingStateLoss();*/
        //userPath = null;
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        //capture the google map object and store it in the gMap variable so that it can be used
        //outside this method
        gMap = googleMap;

        displayMarkers();
        displayPath();
        //handles the "my position" button
        handleRealPositioningOn();
    }

    @Override
    public void onClick(View v) {
        if(v.getId() == R.id.reset_path){
            //the reset path button is clicked

            //reset the view and all related data structures
            Utils.sendMessage(mInertialNavigationService,InertialPedestrianNavigationService.MSG_RESET, null, null);
            estimatedLocationsSet.clear();
            gMap.clear();
            userPath = null;
            steps = 0;
            updateSteps();
        } else if(v.getId() == R.id.calibration_label){
            //show the Calibration Activity
            Intent intent = new Intent(getContext(), CalibrationActivity.class);
            startActivityForResult(intent, WifiLocatorActivity.CALIBRATION_ACTIVITY);
        }

    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        //save the current number of steps
        outState.putInt("steps", steps);
    }

    private final BroadcastReceiver locationEstimationAvailable = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "new location estimation ready!");
            displayMarkers();
        }
    };

    private void displayMarkers(){
        if(gMap != null && app.getEstimatedLocation()!=null){
            //delete the previous marker, if one
                /*if(currentMarker != null) {
                    currentMarker.remove();
                }*/
            //if marker already added in this position the previous time, skip the insertion
            ComparableLocation cl = new ComparableLocation(app.getEstimatedLocation());
            if(!estimatedLocationsSet.contains(cl)) {
                //add this cl to the set
                estimatedLocationsSet.add(cl);

                //add a marker on the map corresponding to the estimated position
                double lat = app.getEstimatedLocation().getLatitude();
                double lon = app.getEstimatedLocation().getLongitude();
                LatLng newMarkerPos = new LatLng(lat, lon);
                //set marker position, icon and title
                MarkerOptions markerOpt = new MarkerOptions();
                markerOpt.position(newMarkerPos);
                markerOpt.icon(BitmapDescriptorFactory.fromResource(R.drawable.position_marker));
                markerOpt.title(app.getEstimatedLocation().getExtras().getString("location_labels"));
                currentMarker = gMap.addMarker(markerOpt);
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newMarkerPos,20));
            }
        }
    }

    private void displayPath(){
        if(gMap != null && !app.getPositionsList().isEmpty()){
            Log.d(TAG, "***** list contains: "+app.getPositionsList().size()+" positions");
            List<LatLng> pathPoints = new ArrayList<>();
            for(PointF p : app.getPositionsList()){
                pathPoints.add(new LatLng(p.y, p.x));
            }
            if(userPath == null){
                PolylineOptions opt = new PolylineOptions();
                opt.width(5);
                opt.color(Color.BLUE);
                opt.addAll(pathPoints);
                opt.endCap(new CustomCap(BitmapDescriptorFactory.fromResource(R.drawable.silver_arrow)));
                userPath = gMap.addPolyline(opt);
            }

            //redraw all the path points
            userPath.setPoints(pathPoints);
        }
    }

    private final BroadcastReceiver inertialPositionAvailable = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "new inertial position ready! "+ app.getPositionsList().get(app.getPositionsList().size()-1).y +"; "+ app.getPositionsList().get(app.getPositionsList().size()-1).x);
            displayPath();
        }
    };

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_STEP:
                    steps++;
                    updateSteps();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }
}
