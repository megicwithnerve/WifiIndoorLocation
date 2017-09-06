package com.unipi.nicola.indoorlocator.inertial;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PointF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;

import com.unipi.nicola.indoorlocator.CalibrationActivity;
import com.unipi.nicola.indoorlocator.FPMapFragment;
import com.unipi.nicola.indoorlocator.IndoorLocatorApplication;
import com.unipi.nicola.indoorlocator.Utils;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;

import java.util.Arrays;
import java.util.TimerTask;

public class InertialPedestrianNavigationService extends Service implements SensorEventListener, StepListener{
    public static final String TAG = "InertialNavService";

    //messages
    public static final int MSG_HELLO_FROM_CALIBRATION_ACTIVITY = 1;
    public static final int MSG_HELLO_FROM_MAP_FRAGMENT = 2;
    public static final int MSG_RESET = 3;
    public static final int MSG_PARAMETERS_CHANGED = 4;
    public static final int MSG_START_CALIBRATION = 5;
    public static final int MSG_SAVE_CALIBRATION = 6;
    public static final int MSG_USE_CALIBRATION = 7;

    private static final float LAT_TO_METERS = 110.574f * 1000;
    private static final float LONG_TO_METERS = 111.320f * 1000;

    IndoorLocatorApplication app;
    Messenger mCalibrationActivity;
    Messenger mMapFragment;

    private static final int CALIBRATION_SAMPLES = 12;
    private static final int CALIBRATION_TIME = 10; //seconds
    private static final float PEDOMETER_SENSITIVITY = 6.66f;

    private SensorManager mSensorManager;
    private Sensor rotationSensor;
    private Sensor accelerometerSensor;

    private Vibrator vibrator; //used for calibration feedback

    //counters for samples to be acquired while calibrating
    private int acquireCalibrationSamples = -1;
    //calibration finite state machine state
    private int calibrationState = -1;
    //private float[] rotationOffsetVector = new float[4];
    private PointF actualPosition;
    private PointF filteredDirection;
    int stepCounter = 0;

    PowerManager.WakeLock wakeLock;

    //transform matrices among the world-phone-user coordinate systems
    RealMatrix[] calibrationMatrices = new RealMatrix[2]; //index 0: northToUFDRotMatrix; index 1: northToPhoneCalibrationMatrix
    //the ufd to phone transform matrix
    RealMatrix ufdToPhoneRotationMatrix = MatrixUtils.createRealIdentityMatrix(3);

    private Location actualEstimatedLocation;

    //The messenger object that must be passed to the activity in order to contact this service
    private Messenger mMessenger = new Messenger(new IncomingHandler());

    //Amount of smoothness for direction filtering
    float beta = 0.5f;
    //Length of a step
    float stepLength = 0.74f; //0.74 meters
    //Time interval after wich the new position is notified
    int updateAfterNumSteps = 3; //steps needed to have an update on the map

    public InertialPedestrianNavigationService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind done");
        return mMessenger.getBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        //initialize the receiver for listening for new locations
        this.registerReceiver(locationEstimationReadyReceiver, new IntentFilter(
                IndoorLocatorApplication.LOCATION_ESTIMATION_READY));

        //initialize the sensors
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometerSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if(rotationSensor != null && accelerometerSensor != null){
            //if the devices has both sensors, then register the listeners
            mSensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL);

            StepDetector stepDetector = new StepDetector();
            stepDetector.addStepListener(this);
            stepDetector.setSensitivity(PEDOMETER_SENSITIVITY);
            mSensorManager.registerListener(stepDetector, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        vibrator = (Vibrator) getBaseContext().getSystemService(Context.VIBRATOR_SERVICE);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MyWakelockTag");
        wakeLock.acquire();

        //initialize the calibration matrix with the one stored in preferences
        CalibrationData cd = CalibrationUtils.getCalibrationInUse(this);
        if(cd == null ){
            ufdToPhoneRotationMatrix = MatrixUtils.createRealIdentityMatrix(3);
        } else {
            ufdToPhoneRotationMatrix = cd.getCalibrationMatrix();
        }

        app = (IndoorLocatorApplication) getApplication();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        wakeLock.release();
        unregisterReceiver(locationEstimationReadyReceiver);
    }

    /**
     * Receives notifications about the new estimations of the position in order to recalibrate the
     * inertial navigation system
     */
    private final transient BroadcastReceiver locationEstimationReadyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //if the previous estimated location equals the new one, then the update is discarded
            if(actualEstimatedLocation != null) {
                String oldId = actualEstimatedLocation.getExtras().getString("id");
                String newId = app.getEstimatedLocation().getExtras().getString("id");
                if (oldId.equals(newId)) {
                    return;
                }
            }
            actualPosition = new PointF(
                    (float) app.getEstimatedLocation().getLongitude(),
                    (float) app.getEstimatedLocation().getLatitude()
            );
            actualEstimatedLocation = new Location(app.getEstimatedLocation());
        }
    };

    /*
     * Handler for requests coming from the activity
     */
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Bundle b = msg.getData();
            switch (msg.what) {
                case MSG_HELLO_FROM_MAP_FRAGMENT:
                    //store the interlocutor
                    mMapFragment = msg.replyTo;
                    Log.d(TAG, "Hello message received from map fragment!");
                    break;
                case MSG_HELLO_FROM_CALIBRATION_ACTIVITY:
                    //store the interlocutor
                    mCalibrationActivity = msg.replyTo;
                    Log.d(TAG, "Hello message received from calibration activity!");
                    break;

                case MSG_RESET:
                    Log.d(TAG,"Reset command received!");
                    app.getPositionsList().clear();
                    actualEstimatedLocation = null;
                    actualPosition = null;
                    break;
                case MSG_PARAMETERS_CHANGED:
                    beta = b.getFloat("beta");
                    Log.d(TAG, "smoothing parameter beta: "+beta);

                    stepLength = b.getFloat("step_length");
                    Log.d(TAG, "step length in meters: "+stepLength);

                    updateAfterNumSteps = b.getInt("update_after_num_steps");
                    Log.d(TAG, "Update after: "+updateAfterNumSteps+" steps");
                    break;
                case MSG_START_CALIBRATION:
                    acquireCalibrationSamples = 0;
                    calibrationState = 0; //acquire north to ufd matrix

                    //starts the timer after which the calibration must end
                    new Handler().postDelayed(new TimerTask() {
                        @Override
                        public void run() {
                            acquireCalibrationSamples = 0;
                            calibrationState = 1; //acquire north to phone matrix
                        }
                    }, CALIBRATION_TIME*1000);
                    break;
                case MSG_SAVE_CALIBRATION:
                    String label = b.getString("calibration_label");
                    boolean success = CalibrationUtils.saveCalibration(InertialPedestrianNavigationService.this, label, (Array2DRowRealMatrix)ufdToPhoneRotationMatrix);
                    Bundle retB = new Bundle();
                    retB.putBoolean("success",success);
                    Utils.sendMessage(mCalibrationActivity, CalibrationActivity.MSG_CALIBRATION_SAVED, retB, null);
                    break;
                case MSG_USE_CALIBRATION:
                    //receive the selected calibration
                    CalibrationData calibration = (CalibrationData) b.getSerializable("selected_calibration");
                    if(calibration == null){
                        //if null, use default
                        ufdToPhoneRotationMatrix = MatrixUtils.createRealIdentityMatrix(3);
                        Log.d(TAG, "Used default calibration");
                    } else {
                        ufdToPhoneRotationMatrix = calibration.getCalibrationMatrix();
                        Log.d(TAG, "Used calibration: "+calibration.getLabel());
                    }

                    printMatrix(ufdToPhoneRotationMatrix);
            }
        }
    }

    //helper methods in order to convert matrix representations
    private static RealMatrix realMatrixFromCoefficients(float[] coeff){
        return MatrixUtils.createRealMatrix(new double[][]
                {{coeff[0], coeff[1], coeff[2]},
                {coeff[3], coeff[4], coeff[5]},
                {coeff[6], coeff[7], coeff[8]}});
    }
    private static float[] coefficientsFromRealMatrix(RealMatrix matrix){
        double[][] rows = new double[][] {matrix.getRow(0), matrix.getRow(1), matrix.getRow(2)};
        return new float[] {(float)rows[0][0],(float)rows[0][1],(float)rows[0][2],
                            (float)rows[1][0],(float)rows[1][1],(float)rows[1][2],
                            (float)rows[2][0],(float)rows[2][1],(float)rows[2][2]};
    }
    private static void printMatrix(RealMatrix matrix){
        double[][] rows = new double[][] {matrix.getRow(0), matrix.getRow(1), matrix.getRow(2)};
        for (double[] row : rows) {
            System.out.println(Arrays.toString(row));
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if(event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR){
            float[] rotationMatrix = new float[9];
            float[] rotationAngles = new float[3];
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);

            //code to compute the mean of the calibration matrices among CALIBRATION_SAMPLES samples,
            //in order to filter them from high frequency noise
            if(calibrationState >= 0){
                if(acquireCalibrationSamples == 0) {
                    //at the beginning, initialize the matrix with the coefficients from the sensor
                    calibrationMatrices[calibrationState] = realMatrixFromCoefficients(rotationMatrix);
                } else if(acquireCalibrationSamples == CALIBRATION_SAMPLES){
                    //at the end, compute the mean of the matrix
                    calibrationMatrices[calibrationState] = calibrationMatrices[calibrationState].scalarMultiply(1/(double)CALIBRATION_SAMPLES);
                    Log.d(TAG, "calibration matrix in state "+calibrationState+" has been computed");
                    // Vibrate for 1 second, as tattile feedback
                    try {
                        vibrator.vibrate(1000);
                    } catch(SecurityException e){
                        e.printStackTrace();
                    }
                    if(calibrationState == 1){
                        //perform the computation of the ufd to phone transform
                        ufdToPhoneRotationMatrix = calibrationMatrices[0].transpose().multiply(calibrationMatrices[1]);
                        Log.d(TAG, "The transpose has determinant: "+new LUDecomposition(calibrationMatrices[0].transpose()).getDeterminant());
                        Log.d(TAG, "ufd to phone matrix computed! It has determinant: "+new LUDecomposition(ufdToPhoneRotationMatrix).getDeterminant());
                        printMatrix(ufdToPhoneRotationMatrix);

                        //notify the map fragment that the calibration is ok
                        Utils.sendMessage(mCalibrationActivity, CalibrationActivity.MSG_CALIBRATION_COMPLETED, null, null);
                    }
                    calibrationState = -1;
                } else {
                    //accumulate samples
                    calibrationMatrices[calibrationState] =
                            calibrationMatrices[calibrationState].add(realMatrixFromCoefficients(rotationMatrix));
                }
                acquireCalibrationSamples++;
            }

            //return to the original space by means of the inverse of the ufdToPhoneRotationMatrix, so that we can evaluate
            //the user direction to respect north
            RealMatrix northToUfdRotationMatrix = realMatrixFromCoefficients(rotationMatrix).multiply(ufdToPhoneRotationMatrix.transpose());

            SensorManager.getOrientation(coefficientsFromRealMatrix(northToUfdRotationMatrix), rotationAngles);
            //Log.d(TAG,"Rotation matrix det: "+new LUDecomposition(northToUfdRotationMatrix).getDeterminant()+"; Azimuth:"+rotationAngles[0]+"; Pitch:"+rotationAngles[1]+"; Roll:"+rotationAngles[2]);
            Log.d(TAG,"Rotated [0 1 0] vector: "+Arrays.toString(northToUfdRotationMatrix.operate(new double[]{0, 1, 0})));
            PointF direction = new PointF(-(float)Math.sin(-rotationAngles[0]), (float)Math.cos(-rotationAngles[0]));
            //TODO: calculate the UFD so that phone can be held in any position
            //NOTE: direction must be a normalized vector

            //filter the direction vector
            filterDirection(direction);

        }
    }

    @Override
    public void onStep() {
        //called when a new step is detected
        stepCounter++;
        Utils.sendMessage(mMapFragment, FPMapFragment.MSG_STEP, null, null);

        //update the position using the current direction. position is needed as lat lon coordinates
        //https://stackoverflow.com/questions/1253499/simple-calculations-for-working-with-lat-lon-km-distance
        if(filteredDirection != null && actualPosition != null) {
            actualPosition.y += filteredDirection.y * stepLength * (1 / LAT_TO_METERS);
            actualPosition.x += filteredDirection.x * stepLength * (1 / (LONG_TO_METERS * Math.cos(actualPosition.y * Math.PI / 180)));
            if (stepCounter % updateAfterNumSteps == 0) {
                //send the new estimated position in a broadcast intent
                sendInertialPosition();
            }
        }
    }

    private void filterDirection(PointF direction){
        if(filteredDirection == null){
            filteredDirection = new PointF(direction.x, direction.y);
        } else {
            filteredDirection.x = filteredDirection.x * beta + direction.x * (1 - beta);
            filteredDirection.y = filteredDirection.y * beta + direction.y * (1 - beta);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    private void sendInertialPosition(){
        app.getPositionsList().add(new PointF(actualPosition.x, actualPosition.y));

        //notifies all the broadcast receivers for this new data
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(IndoorLocatorApplication.NEW_INERTIAL_POSITION_AVAILABLE);
        sendBroadcast(broadcastIntent);
        Log.d(TAG, "New inertial position sent!");
    }
}
