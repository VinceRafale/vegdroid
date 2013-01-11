package de.thiemonagel.vegdroid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreProtocolPNames;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.telephony.TelephonyManager;
import android.util.FloatMath;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

// Singleton containing data to be displayed
public class MyData {
    private static final boolean                     DEBUG         = false;

    private static final String                      PREFS_FILE    = "config";
    private static final String                      PREFS_CATMASK = "CategoryMask";
    private static final String                      LOG_TAG       = "VegDroid";

    private static final int                         YARDS         = 1760;   // 1760 yards in a mile, who invented that?

    private static volatile MyData                   fInstance = null;

    private Context                                  fContext;               // required for location manager, among others
    private int                                      fCatFilterMask;
    private int                                      fCatFilterMaskApplied;  // last mask committed
    private ArrayList<HashMap<String, String>>       fDataList;              // data currently to be displayed
    private HashMap<String, HashMap<String, String>> fDataMap;               // cache of full information
    private String                                   fError;                 // error message
    private int                                      fNumEntryLimit;         // number of entries to be pulled from server
    private boolean                                  fkm;                    // whether distances are to be displayed in km
    private Location                                 fCurrentLoc;            // current location
    private Location                                 fLastLoc;               // last location for which data have been loaded
    private Date                                     fLastDate;              // last time data have been loaded
    private SharedPreferences                        fSettings;


    private MyData( Context c ) {
        fDataList      = new ArrayList<HashMap<String, String>>();
        fDataMap       = new HashMap<String,HashMap<String, String>>();
        fContext       = c;
        fError         = "";
        fNumEntryLimit = 50;
        fCurrentLoc    = null;
        fLastLoc       = null;
        fLastDate      = null;

        // derive preferred units from SIM card country
        TelephonyManager tm = (TelephonyManager)fContext.getSystemService(Context.TELEPHONY_SERVICE);
        String ISO = tm.getSimCountryIso().toLowerCase();
        Log.i( LOG_TAG, "SIM country ISO: " + ISO );

        // it seems that only USA, GB, Liberia and Burma still use miles:
        // https://en.wikipedia.org/wiki/Imperial_units#Current_use_of_imperial_units
        // https://www.cia.gov/library/publications/the-world-factbook/appendix/appendix-g.html
        // https://en.wikipedia.org/wiki/Burmese_units_of_measurement
        if (    ISO.equals("gb")   // Great Britain
             || ISO.equals("io")   // British Indian Ocean Territory
             || ISO.equals("lr")   // Liberia
             || ISO.equals("mm")   // Burma
             || ISO.equals("uk")   // bad ISO code, checking it nevertheless, just in case...
             || ISO.equals("um")   // U.S. Minor Outlying Islands
             || ISO.equals("us")   // U.S.A.
             || ISO.equals("vg")   // British Virgin Islands
             || ISO.equals("vi") ) // U.S. Virgin Islands
            fkm = false;
        else
            fkm = true;

        // load from SharedPreferences
        fSettings             = c.getSharedPreferences( PREFS_FILE, Context.MODE_PRIVATE );
        fCatFilterMask        = fSettings.getInt( PREFS_CATMASK, -1 );
        fCatFilterMaskApplied = fCatFilterMask;
        Log.d( LOG_TAG, "Read CatFilterMask: " + fCatFilterMask );
    }

    // Obtain instance, constructing it if necessary.  Should be called in onCreate() of
    // all activity that makes use of MyData.
    public static MyData initInstance( Context c ) {
        if ( fInstance == null ) {
            synchronized (MyData.class) {
                if ( fInstance == null ) {
                    // make sure that app context is used which is valid for the whole run time of the app
                    Context appContext = c.getApplicationContext();
                    fInstance = new MyData( appContext );
                }
            }
        }
        return fInstance;
    }

    // Obtain instance, relying on the fact that it is already existing.
    public static MyData getInstance() {
        assert( fInstance != null );
        return fInstance;
    }

    public String getError()   { return fError; }
    public void   clearError() { fError = "";   }

    // set category index to value val
    public void setCatFilter( int index, boolean val ) {
        if ( val )
            fCatFilterMask |= (1<<index);
        else
            fCatFilterMask &= ~(1<<index);
    }

    public void setCatFilter( int mask ) {
        fCatFilterMask = mask;
    }

    public int getCatFilter() {
        return fCatFilterMask;
    }

    public boolean[] getCatFilterBool() {
        String[] list = fContext.getResources().getStringArray(R.array.categories);
        int len = list.length;
        boolean[] ret = new boolean[len];
        for ( int mask = fCatFilterMask, i = 0; mask != 0 && i < len; mask >>>= 1, i++ )
            ret[i] = (mask&1)==1 ? true : false;
        return ret;
    }

    // commit to SharedPreferences
    public void commitCatFilter() {
        if ( fCatFilterMask == fCatFilterMaskApplied )
            return;

        SharedPreferences.Editor editor = fSettings.edit();
        editor.putInt( PREFS_CATMASK, fCatFilterMask );
        editor.commit();   // TODO: use apply() instead, requires API 9
        fCatFilterMaskApplied = fCatFilterMask;
    }

    // return global map
    public HashMap<String, HashMap<String, String>> getMap() {
        return fDataMap;
    }

    // return current display list (possibly filtered)
    public ArrayList<HashMap<String, String>> getList() {
        return fDataList;
    }

    // recreate current display list (must be run after data has been loaded or
    // filters have been updated)
    public void updateList() {
        // empty list
        fDataList.clear();

        // filter
        for ( Map.Entry<String, HashMap<String, String>> entry : fDataMap.entrySet() ) {
            String[] list = fContext.getResources().getStringArray(R.array.categories);
            boolean valid = false;
            for ( int mask = fCatFilterMask, i = 0; mask != 0 && i < list.length; mask >>>= 1, i++ ) {
                if ( (mask & 1) == 0 ) continue;
                if ( entry.getValue().get("categories").contains( list[i] ) ) {
                    valid = true;
                    break;
                }
            }
            if ( valid )
                fDataList.add( entry.getValue() );
        }

        // sort
        Collections.sort( fDataList, new Comparator<HashMap<String, String>>() {
            public int compare(HashMap<String, String> a, HashMap<String, String> b) {
                String adist = a.get("pdistance");
                String bdist = b.get("pdistance");
                if ( adist == null )
                    return 1;
                if ( bdist == null )
                    return -1;
                if ( adist.compareTo(bdist) < 0 )
                    return -1;
                else
                    return 1;
            }
        });
    }

    // access current location
    protected LatLng getLocation() {
        if ( fCurrentLoc == null )
            return null;
        else
            return new LatLng( fCurrentLoc.getLatitude(), fCurrentLoc.getLongitude() );
    }

    // return success if location could be obtained
    boolean UpdateLocation() {
        // find location
        LocationManager lMan = (LocationManager) fContext.getSystemService(Context.LOCATION_SERVICE);
        List<String> lproviders = lMan.getProviders( false );  // true = enabled only
        Log.d( LOG_TAG, lproviders.size() + " location providers found." );
        for ( String prov : lproviders ) {
            Location l = lMan.getLastKnownLocation(prov);

            String logstr = prov + ": ";
            if ( l != null ) {
                logstr += l.getLatitude();
                logstr += ", " + l.getLongitude();
                logstr += ", time: " + l.getTime();
                if ( l.hasAccuracy() ) {
                    logstr += ", error: " + l.getAccuracy() + " m";
                }
            } else {
                logstr += "[empty]";
            }
            Log.d( LOG_TAG, logstr );

            if ( l == null )
                continue;

            if ( fCurrentLoc == null ) {
                fCurrentLoc = l;
                continue;
            }

            // if one reading doesn't have accuracy, the latest is preferred
            if ( !fCurrentLoc.hasAccuracy() || !l.hasAccuracy() ) {
                if ( l.getTime() > fCurrentLoc.getTime() ) {
                    fCurrentLoc = l;
                }
                continue;
            }

            long  btime = fCurrentLoc.getTime();     // ms
            long  ltime = l.getTime();               // ms
            float bacc  = fCurrentLoc.getAccuracy(); // m
            float lacc  = l.getAccuracy();           // m

            // both have accuracy, l is more recent and more accurate
            if ( ltime > btime && lacc < bacc ) {
                fCurrentLoc = l;
                continue;
            }

            long  tdist = ltime - btime;
            float dist  = l.distanceTo( fCurrentLoc );
            // agreement in sigmas
            float agr  = dist / FloatMath.sqrt( bacc*bacc + lacc*lacc );

            // use outdated but more precise measurement only
            // when agreement isn't too bad and time difference isn't
            // too large
            float crit = 1e5f / tdist;
            if ( crit < 3f ) { crit = 3f; }
            if ( agr < crit ) {
                if ( lacc < bacc ) {
                    fCurrentLoc = l;
                }
            } else {
                if ( ltime > btime ) {
                    fCurrentLoc = l;
                }
            }
        }

        if ( fCurrentLoc == null ) {
            if ( DEBUG ) {
                // set bogus location for debugging
                Log.i( LOG_TAG, "No location found." );
                //url += "0,0";
                fCurrentLoc = new Location("");
                fCurrentLoc.setLatitude (48.139126);
                fCurrentLoc.setLongitude(11.580186);
                fCurrentLoc.setAccuracy (100.f);
            } else {
                // abort with error
                fError = "Location could not be determined!";
                return false;
            }
        }
        return true;
    }

    // pull data from vegguide.org and decode JSON into fDataMap
    // return true on success
    protected boolean Load() {

        // skip loading of data when location has changed less than 50 meters and previously received
        // data is less than one day old
        Date now = new Date();
        if ( fCurrentLoc != null && fLastLoc != null && fLastDate != null
             && fCurrentLoc.distanceTo(fLastLoc) < 50f
             && (now.getTime()-fLastDate.getTime()) /1000 /3600 /24 == 0 )
            return true;

        int roundDigits;
        float roundMultiplier;  // for km/miles
        float locationAccuracy = fCurrentLoc.getAccuracy() / ( fkm ? 1000f : 1609.344f );
        if ( locationAccuracy < .015f ) {
            roundMultiplier = ( fkm ? 1000f : YARDS );
            roundDigits     = 3;
        } else if ( locationAccuracy < .15f ) {
            roundMultiplier = ( fkm ? 100f : YARDS/10f );
            roundDigits     = 2;
        } else if ( locationAccuracy < 1.5f ) {
            roundMultiplier = ( fkm ? 10f : YARDS/100f );
            roundDigits     = 1;
        } else {
            roundMultiplier = 1f;
            roundDigits     = 0;
        }
        Log.d( LOG_TAG, "roundMultiplier: " + roundMultiplier );

        // By default, the website imposes a 5km limit, but I prefer to show the
        // closest venues, even if they are thousands of miles away.
        String url = "http://www.vegguide.org/search/by-lat-long/"
                + fCurrentLoc.getLatitude() + "," + fCurrentLoc.getLongitude()
                + "?unit=km&distance=100000&limit=" + fNumEntryLimit;

        Log.i( LOG_TAG, "Getting: " +url );
        HttpClient client = new DefaultHttpClient();  // Apache HTTP client
        client.getParams().setParameter( CoreProtocolPNames.USER_AGENT, R.string.app_name + " " + R.string.version_string );
        HttpGet httpGet = new HttpGet( url );
        StringBuilder builder = new StringBuilder();
        try {
            HttpResponse response = client.execute(httpGet);
            StatusLine statusLine = response.getStatusLine();
            int statusCode = statusLine.getStatusCode();
            if (statusCode == 200) {
                HttpEntity entity = response.getEntity();
                Log.i( LOG_TAG, "Received " + (entity.getContentLength()>>10) + " kiB of entry data." );
                InputStream content = entity.getContent();
                BufferedReader reader = new BufferedReader(new InputStreamReader(content));
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            } else {
                fError = "Bad server status code: " + statusCode;
                return false;
            }
        } catch (ClientProtocolException e) {
            e.printStackTrace();
            fError = "ClientProtocolException";
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            fError = "IOException";
            return false;
        }

        Log.v( LOG_TAG, builder.toString() );

        try {
            JSONObject json = new JSONObject(builder.toString());
            JSONArray entries = json.getJSONArray("entries");
            for ( int i = 0; i < entries.length(); i++ ) {
                JSONObject entry = entries.getJSONObject(i);
    //            Log.v( LOG_TAG, entry.getString("name") );

                HashMap<String, String> map = new HashMap<String, String>();
                String keylist[] = {
                        "address1", "address2", "close_date", "city", "distance",
                        "name", "neighborhood", "phone", "postal_code",
                        "price_range", "short_description", "uri",
                        "veg_level", "veg_level_description", "website",
                        "weighted_rating" };

                // Missing keys are set to empty strings, as the API specifies:
                // "If a key's value would be null, an empty string, or an empty array, it is always omitted."
                for ( String key : keylist ) {
                    String s = "";
                    try {
                        s = entry.getString(key);
                    } catch (JSONException e) {};
                    map.put( key, s );
                }

                // skip closed entries
                if ( !map.get("close_date").equals("") ) {
                    SimpleDateFormat ft = new SimpleDateFormat ("yyyy-MM-dd");
                    try {
                        Date closed = ft.parse( map.get("close_date") );
                        if ( closed.before(now) )
                            continue;
                    } catch (ParseException e) {
                        Log.e( LOG_TAG, "closed_date parse error!" );
                    }
                }

                // long description (use short description if long one is missing)
                String ldes;
                try {
                    ldes = entry.getJSONObject("long_description").getString("text/html");
                } catch (JSONException e) { ldes = map.get("short_description"); }
                map.put( "long_description", ldes );

                // all categories in one string
                try {
                    String cats;
                    JSONArray acats = entry.getJSONArray( "categories" );
                    cats = "(";
                    for ( int j = 0; j < acats.length(); j++ ) {
                        if ( cats != "(" ) {
                            cats += ", ";
                        }
                        cats += acats.getString(j);
                    }
                    cats += ")";
                    map.put( "categories", cats );
                } catch (JSONException e) { map.put( "categories", "" ); }

                // cats and veg_level in one string
                try {
                    String cats_vlevel;
                    JSONArray acats = entry.getJSONArray( "categories" );
                    cats_vlevel = "";
                    for ( int j = 0; j < acats.length(); j++ ) {
                        if ( cats_vlevel != "" ) {
                            cats_vlevel += ", ";
                        }
                        cats_vlevel += acats.getString(j);
                    }

                    cats_vlevel += " -- " + entry.getString( "veg_level_description" ).toLowerCase();
                    map.put( "cats_vlevel", cats_vlevel );
                } catch (JSONException e) { map.put( "cats_vlevel", "" ); }

                // store results
                try {
                    String uri = entry.getString("uri");
                    // add to cache
                    fDataMap.put( uri, map );

                    String sd = map.get("distance");
                    float  fd = 1e10f;
                    try {
                        fd = Float.parseFloat(sd);
                    } catch (Throwable e) {};
                    map.put("pdistance", String.format("%10.3f", fd) );  // precise distance in km, for sorting
                    if ( !fkm ) fd /= 1.609344;  // international yard and pound treaty (1959)
                    fd = Math.round(fd*roundMultiplier) / roundMultiplier;
                    if ( fd < 1f )
                        if ( fkm )
                            sd = String.format( "%.0f m", fd*1000 );
                        else
                            sd = String.format( "%.0f yds", fd*YARDS );
                    else
                        sd = String.format( "%."+roundDigits+"f %s", fd, ( fkm ? " km" : " miles" ) );
                    map.put("distance", sd);

                } catch (JSONException e) {
                    Log.e( LOG_TAG, "uri missing!" );
                    fError = "URI missing!";
                    return false;
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
            fError = "JSONException";
            return false;
        }

        fLastLoc  = fCurrentLoc;
        fLastDate = now;
        return true;
    }
}
