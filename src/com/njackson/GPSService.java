package com.njackson;

import java.util.List;
import java.util.UUID;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GpsStatus.NmeaListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import fr.jayps.android.AdvancedLocation;

/**
 * Created with IntelliJ IDEA.
 * User: njackson
 * Date: 23/05/2013
 * Time: 13:30
 * To change this template use File | Settings | File Templates.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class GPSService extends Service {
	
	private static final String TAG = "PB-GPSService";

    private int _updates;
    private float _speed;
    private float _averageSpeed;
    private float _distance;

    private float _prevspeed = -1;
    private float _prevaverageSpeed = -1;
    private float _prevdistance = -1;
    private double _prevaltitude = -1;
    private long _prevtime = -1;
    private long _lastSaveGPSTime = 0;
    private double _currentLat;
    private double _currentLon;
    double xpos = 0;
    double ypos = 0;
    Location firstLocation = null;
    private AdvancedLocation _myLocation;
    private LiveTracking _liveTracking;
    
    private static GPSService _this;
    
    private int _refresh_interval = 1000;
    private boolean _gpsStarted = false;

    private SensorManager _mSensorMgr = null;

    // BLE HRM
    private String mDeviceAddress;
    private BluetoothLeService mBluetoothLeService;
    private boolean mBLEConnected = false;
    private BluetoothGattCharacteristic mNotifyCharacteristic;
    public final static UUID UUID_HEART_RATE_MEASUREMENT = UUID.fromString(BLESampleGattAttributes.HEART_RATE_MEASUREMENT);
    public int heart_rate = -1;
    private long heart_rate_ts = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handleCommand(intent);
        _this = this;
        
        makeServiceForeground("Pebble Bike", "GPS started");
        return START_STICKY;
    }
    
    @Override
    public void onCreate() {
        _locationMgr = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        _mSensorMgr = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    private boolean checkGPSEnabled(LocationManager locationMgr) {

        if(!locationMgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
           return false;
        } else {
            return true;
        }

    }

    @Override
    public void onDestroy (){
        Log.d(TAG, "Stopped GPS Service");
        
        saveGPSStats();

        _this = null;
        
        removeServiceForeground();
        
        if (!MainActivity.oruxmaps_autostart.equals("disable")) {
            OruxMaps.stopRecord(getApplicationContext());
        }
        
        //PebbleKit.closeAppOnPebble(getApplicationContext(), Constants.WATCH_UUID);

        _locationMgr.removeUpdates(onLocationChange);
        _locationMgr.removeNmeaListener(mNmeaListener);
        
        _mSensorMgr.unregisterListener(mSensorListener);

        if (mBluetoothLeService != null) {
            unbindService(mServiceConnection);
            mBluetoothLeService = null;
            unregisterReceiver(mGattUpdateReceiver);
        }
    }

    // load the saved state
    public void loadGPSStats() {
    	Log.d(TAG, "loadGPSStats()");
    	
    	SharedPreferences settings = getSharedPreferences(Constants.PREFS_NAME,0);
        _speed = settings.getFloat("GPS_SPEED",0.0f);
        _distance = settings.getFloat("GPS_DISTANCE",0.0f);
        _myLocation.setDistance(_distance);
        _myLocation.setElapsedTime(settings.getLong("GPS_ELAPSEDTIME", 0));
        
        try {
            _myLocation.setAscent(settings.getFloat("GPS_ASCENT", 0.0f));
        } catch (ClassCastException e) {
            _myLocation.setAscent(0.0);
        }
        try {
            _updates = settings.getInt("GPS_UPDATES",0);
        } catch (ClassCastException e) {
            _updates = 0;
        }
        
        if (settings.contains("GPS_FIRST_LOCATION_LAT") && settings.contains("GPS_FIRST_LOCATION_LON")) {
            firstLocation = new Location("PebbleBike");
            firstLocation.setLatitude(settings.getFloat("GPS_FIRST_LOCATION_LAT", 0.0f));
            firstLocation.setLongitude(settings.getFloat("GPS_FIRST_LOCATION_LON", 0.0f));
        } else {
            firstLocation = null;
        }
        
    }

    // save the state
    public void saveGPSStats() {
        if (MainActivity.debug) Log.d(TAG, "saveGPSStats()");
    	
        SharedPreferences settings = getSharedPreferences(Constants.PREFS_NAME,0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putFloat("GPS_SPEED", _speed);
        editor.putFloat("GPS_DISTANCE",_distance);
        editor.putLong("GPS_ELAPSEDTIME", _myLocation.getElapsedTime());
        editor.putFloat("GPS_ASCENT", (float) _myLocation.getAscent());
        editor.putInt("GPS_UPDATES", _updates);
        if (firstLocation != null) {
            editor.putFloat("GPS_FIRST_LOCATION_LAT", (float) firstLocation.getLatitude());
            editor.putFloat("GPS_FIRST_LOCATION_LON", (float) firstLocation.getLongitude());
        }
        editor.commit();
    }

    // reset the saved state
    public static void resetGPSStats(SharedPreferences settings) {
    	Log.d(TAG, "resetGPSStats()");
    	
	    SharedPreferences.Editor editor = settings.edit();
	    editor.putFloat("GPS_SPEED", 0.0f);
	    editor.putFloat("GPS_DISTANCE",0.0f);
	    editor.putLong("GPS_ELAPSEDTIME", 0);
	    editor.putFloat("GPS_ASCENT", 0.0f);
	    editor.putInt("GPS_UPDATES", 0);
        editor.remove("GPS_FIRST_LOCATION_LAT");
        editor.remove("GPS_FIRST_LOCATION_LON");
	    editor.commit();
	    
	    if (_this != null) {
	    	// GPS is running
		    // reninit all properties
	    	_this._myLocation = new AdvancedLocation(_this.getApplicationContext());
	    	_this._myLocation.debugLevel = MainActivity.debug ? 2 : 0;
	    	_this._myLocation.debugTagPrefix = "PB-";

	    	_this.loadGPSStats();  	    	
	    }
    }
    public static void changeRefreshInterval(int refresh_interval) {
        if (_this != null) {
            // GPS is running
            _this._refresh_interval = refresh_interval;
            _this._requestLocationUpdates(refresh_interval);
        }
    }

    private void handleCommand(Intent intent) {
        Log.d(TAG, "Started GPS Service");
        
        _liveTracking = new LiveTracking(getApplicationContext());

        _myLocation = new AdvancedLocation(getApplicationContext());
        _myLocation.debugLevel = MainActivity.debug ? 2 : 0;
        _myLocation.debugTagPrefix = "PB-";

        loadGPSStats();
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        	 
        _liveTracking.setLogin(prefs.getString("LIVE_TRACKING_LOGIN", ""));
        _liveTracking.setPassword(prefs.getString("LIVE_TRACKING_PASSWORD", ""));
        _liveTracking.setUrl(prefs.getString("LIVE_TRACKING_URL", ""));

        // check to see if GPS is enabled
        if(checkGPSEnabled(_locationMgr)) {
            _requestLocationUpdates(intent.getIntExtra("REFRESH_INTERVAL", 1000));

            SharedPreferences settings = getSharedPreferences(Constants.PREFS_NAME,0);
            
            if (MainActivity.oruxmaps_autostart.equals("continue")) {
                OruxMaps.startRecordContinue(getApplicationContext());
            } else if (MainActivity.oruxmaps_autostart.equals("new_segment")) {
                OruxMaps.startRecordNewSegment(getApplicationContext());
            } else if (MainActivity.oruxmaps_autostart.equals("new_track")) {
                OruxMaps.startRecordNewTrack(getApplicationContext());
            } else if (MainActivity.oruxmaps_autostart.equals("auto")) {
                long last_start = settings.getLong("GPS_LAST_START", 0);
                if (System.currentTimeMillis() - last_start > 12 * 3600 * 1000) { // 12 hours
                    OruxMaps.startRecordNewTrack(getApplicationContext());
                } else {
                    OruxMaps.startRecordNewSegment(getApplicationContext());
                }
            }
            
            SharedPreferences.Editor editor = settings.edit();
            editor.putLong("GPS_LAST_START", System.currentTimeMillis());
            editor.commit();
            
            // send the saved values directly to update pebble
            Intent broadcastIntent = new Intent();
            broadcastIntent.setAction(MainActivity.GPSServiceReceiver.ACTION_RESP);
            broadcastIntent.putExtra("DISTANCE", _myLocation.getDistance());
            broadcastIntent.putExtra("AVGSPEED", _myLocation.getAverageSpeed());
            broadcastIntent.putExtra("ASCENT",   _myLocation.getAscent());
            sendBroadcast(broadcastIntent);
        }else {
            Intent broadcastIntent = new Intent();
            broadcastIntent.setAction(MainActivity.GPSServiceReceiver.ACTION_GPS_DISABLED);
            sendBroadcast(broadcastIntent);
            return;
        }

        // delay between events in microseconds
        _mSensorMgr.registerListener(mSensorListener, _mSensorMgr.getDefaultSensor(Sensor.TYPE_PRESSURE), 3000000);
        
        if (!MainActivity.hrm_address.equals("")) {
            // start BLE Heart Rate Monitor
            mDeviceAddress = MainActivity.hrm_address;
            Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
            bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

            registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        }
        
        //PebbleKit.startAppOnPebble(getApplicationContext(), Constants.WATCH_UUID);
    }
    private void _requestLocationUpdates(int refresh_interval) {
        Log.d(TAG, "_requestLocationUpdates("+refresh_interval+")");
        _refresh_interval = refresh_interval;

        if (_gpsStarted) {
            _locationMgr.removeUpdates(onLocationChange);
        }
        _locationMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, _refresh_interval, 2, onLocationChange);
        if (MainActivity.geoidHeight != 0.0) {
            // already got a correction, use it
            _myLocation.setGeoidHeight(MainActivity.geoidHeight);
        } else {
            // request Nmea updates to get a geoid height
            _locationMgr.addNmeaListener(mNmeaListener);
        }
        _gpsStarted = true;
    }

    private LocationManager _locationMgr = null;
    private LocationListener onLocationChange = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            int resultOnLocationChanged = _myLocation.onLocationChanged(location);
            
            //Log.d(TAG,  "onLocationChanged: " + _myLocation.getTime() + " Accuracy: " + _myLocation.getAccuracy());

            _speed = _myLocation.getSpeed();

            if(_speed < 1) {
                _speed = 0;
            } else {
                _updates++;
            }

            _averageSpeed = _myLocation.getAverageSpeed();
            _distance = _myLocation.getDistance();

            _currentLat = location.getLatitude();
            _currentLon = location.getLongitude();
            
            if (firstLocation == null) {
                firstLocation = location;
            }

            xpos = firstLocation.distanceTo(location) * Math.sin(firstLocation.bearingTo(location)/180*3.1415);
            ypos = firstLocation.distanceTo(location) * Math.cos(firstLocation.bearingTo(location)/180*3.1415); 

            xpos = Math.floor(xpos/10);
            ypos = Math.floor(ypos/10);
            if (MainActivity.debug) Log.d(TAG,  "xpos="+xpos+"-ypos="+ypos);

            boolean send = false;
            //if(_myLocation.getAccuracy() < 15.0) // not really needed, something similar is done in AdvancedLocation
            if (_speed != _prevspeed || _averageSpeed != _prevaverageSpeed || _distance != _prevdistance || _prevaltitude != _myLocation.getAltitude()) {

                send = true;

                _prevaverageSpeed = _averageSpeed;
                _prevdistance = _distance;
                _prevspeed = _speed;
                _prevaltitude = _myLocation.getAltitude();
                _prevtime = _myLocation.getTime();
            } else if (_prevtime + 5000 < _myLocation.getTime()) {
                if (MainActivity.debug) Log.d(TAG,  "New GPS data without move");
                
                send = true;
                
                _prevtime = _myLocation.getTime();
            }
            if (send) {
                broadcastLocation();

                if (_lastSaveGPSTime == 0 || (_myLocation.getTime() - _lastSaveGPSTime > 60000)) {
                    saveGPSStats();
                    _lastSaveGPSTime = _myLocation.getTime();
                }
            }

            if (MainActivity._liveTracking && resultOnLocationChanged == AdvancedLocation.SAVED) {
                _liveTracking.addPoint(firstLocation, location);
            }
            
        }

        @Override
        public void onProviderDisabled(String arg0) {
            // TODO Auto-generated method stub
            
        }

        @Override
        public void onProviderEnabled(String arg0) {
            // TODO Auto-generated method stub
            
        }

        @Override
        public void onStatusChanged(String arg0, int arg1, Bundle arg2) {
            // TODO Auto-generated method stub
            
        }        
    };

    NmeaListener mNmeaListener = new NmeaListener() {
        @Override
        public void onNmeaReceived(long timestamp, String nmea) {
           //Log.d(TAG, "Received some nmea strings: " + nmea);
           if (nmea.startsWith("$GPGGA")) {
               // http://aprs.gids.nl/nmea/#gga
               //Log.d(TAG, "geoid: " + nmea);

               String[] strValues = nmea.split(",");
               /*
               Log.d(TAG, "nmea 7 nb sat: " + strValues[7]);
               Log.d(TAG, "nmea 8 hdop: " + strValues[8]);
               Log.d(TAG, "nmea 11 geoid_height: " + strValues[11]);
               */
               try {
                   // Height of geoid above WGS84 ellipsoid
                   double geoid_height = Double.parseDouble(strValues[11]);

                   if (MainActivity.debug) Log.d(TAG, "nmea geoid_height: " + geoid_height);
                   _myLocation.setGeoidHeight(geoid_height);
                   MainActivity.geoidHeight = geoid_height;

                   // no longer need Nmea updates
                   _locationMgr.removeNmeaListener(mNmeaListener);
               } catch (Exception e) {
               }
           }
        }
    };

    private SensorEventListener mSensorListener = new SensorEventListener() {

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            //Log.d(TAG, "onAccuracyChanged" + accuracy);
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            //Log.d(TAG, "onSensorChanged sensor:" + event.sensor.getName() + " type:"+event.sensor.getType());

            float pressure_value = 0.0f;
            double altitude = 0.0f;

            // we register to TYPE_PRESSURE, so we don't really need this test
            if( Sensor.TYPE_PRESSURE == event.sensor.getType()) {
                pressure_value = event.values[0];
                altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure_value);
                if (MainActivity.debug) Log.d(TAG, "pressure_value=" + pressure_value + " altitude=" + altitude);

                _myLocation.onAltitudeChanged(altitude);

                broadcastLocation();
            }
        }
    };
    
    private void broadcastLocation() {
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(MainActivity.GPSServiceReceiver.ACTION_RESP);
        broadcastIntent.addCategory(Intent.CATEGORY_DEFAULT);
        broadcastIntent.putExtra("SPEED",       _myLocation.getSpeed());
        broadcastIntent.putExtra("DISTANCE",    _myLocation.getDistance());
        broadcastIntent.putExtra("AVGSPEED",    _myLocation.getAverageSpeed());
        broadcastIntent.putExtra("LAT",         _myLocation.getLatitude());
        broadcastIntent.putExtra("LON",         _myLocation.getLongitude());
        broadcastIntent.putExtra("ALTITUDE",    _myLocation.getAltitude()); // m
        broadcastIntent.putExtra("ASCENT",      _myLocation.getAscent()); // m
        broadcastIntent.putExtra("ASCENTRATE",  (3600f * _myLocation.getAscentRate())); // in m/h
        broadcastIntent.putExtra("SLOPE",       (100f * _myLocation.getSlope())); // in %
        broadcastIntent.putExtra("ACCURACY",   _myLocation.getAccuracy()); // m
        broadcastIntent.putExtra("TIME",        _myLocation.getElapsedTime());
        broadcastIntent.putExtra("XPOS",        xpos);
        broadcastIntent.putExtra("YPOS",        ypos);
        broadcastIntent.putExtra("BEARING",     _myLocation.getBearing());
        if (heart_rate >= 0) {
            if (System.currentTimeMillis() - heart_rate_ts < 5000) {
                broadcastIntent.putExtra("HEARTRATE", heart_rate);
            } else {
                broadcastIntent.putExtra("HEARTRATE", 0);
            }
        }
        sendBroadcast(broadcastIntent);
    }

    private void makeServiceForeground(String titre, String texte) {
        //http://stackoverflow.com/questions/3687200/implement-startforeground-method-in-android
        final int myID = 1000;

        //The intent to launch when the user clicks the expanded notification
        Intent i = new Intent(this, MainActivity.class);

        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendIntent = PendingIntent.getActivity(this, 0, i, 0);

        // The following code is deprecated since API 11 (Android 3.x). Notification.Builder could be used instead, but without Android 2.x compatibility 
        Notification notification = new Notification(R.drawable.ic_launcher, "Pebble Bike", System.currentTimeMillis());
        notification.setLatestEventInfo(this, titre, texte, pendIntent);

        notification.flags |= Notification.FLAG_NO_CLEAR;

        startForeground(myID, notification);
    }
    private void removeServiceForeground() {
        stopForeground(true);
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                //finish();
                return;
            }

            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            if (MainActivity.debug) Log.d(TAG, "BluetoothLeService onServiceDisconnected");
            mBluetoothLeService = null;
        }
    };
    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                if (MainActivity.debug) Log.d(TAG, "ACTION_GATT_CONNECTED");
                mBLEConnected = true;
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                if (MainActivity.debug) Log.d(TAG, "ACTION_GATT_DISCONNECTED");
                mBLEConnected = false;

                if (mBluetoothLeService != null) {
                    final boolean result = mBluetoothLeService.connect(mDeviceAddress);
                    if (MainActivity.debug) Log.d(TAG, "Connect request result=" + result);
                }

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface
                if (MainActivity.debug) Log.d(TAG, "ACTION_GATT_SERVICES_DISCOVERED");
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                //Log.d(TAG, "ACTION_DATA_AVAILABLE");
                if (intent.hasExtra(BluetoothLeService.EXTRA_HEART_RATE)) {
                    heart_rate = intent.getIntExtra(BluetoothLeService.EXTRA_HEART_RATE, -1);
                    heart_rate_ts = System.currentTimeMillis();
                    if (MainActivity.debug) Log.d(TAG, "heart_rate:" + heart_rate);
                    broadcastLocation();
                }
            }
        }
    };
    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }
    private void displayGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {

            List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {

                if (UUID_HEART_RATE_MEASUREMENT.equals(gattCharacteristic.getUuid())) {
                    //if (MainActivity.debug) Log.d(TAG, "UUID_HEART_RATE_MEASUREMENT!");
                    int charaProp = gattCharacteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                        // If there is an active notification on a characteristic, clear
                        // it first so it doesn't update the data field on the user interface.
                        if (mNotifyCharacteristic != null) {
                            mBluetoothLeService.setCharacteristicNotification(mNotifyCharacteristic, false);
                            mNotifyCharacteristic = null;
                        }
                        //if (MainActivity.debug) Log.d(TAG, "readCharacteristic: " + gattCharacteristic.getUuid().toString());
                        mBluetoothLeService.readCharacteristic(gattCharacteristic);
                    }
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                        mNotifyCharacteristic = gattCharacteristic;
                        //if (MainActivity.debug) Log.d(TAG, "setCharacteristicNotification: " + gattCharacteristic.getUuid().toString());
                        mBluetoothLeService.setCharacteristicNotification(gattCharacteristic, true);
                    }
                }
            }
        }
    }
}
