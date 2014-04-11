package com.njackson.events.GPSService;

import java.security.InvalidParameterException;

/**
 * Created by server on 11/04/2014.
 */
public class NewAltitiudeEvent {

    private final int VALUES_SIZE = 14;

    private float[] _altitudeValues;
    private int _maxAltitude;


    public NewAltitiudeEvent(float[] altitudeValues,int maxAltitude) {
        if(altitudeValues == null || altitudeValues.length != VALUES_SIZE)
            throw new InvalidParameterException("Constructor requires altitude values with size" + VALUES_SIZE);

        _altitudeValues = altitudeValues;
        _maxAltitude = maxAltitude;
    }
}
