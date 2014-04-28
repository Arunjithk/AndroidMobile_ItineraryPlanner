package com.codepath.travelplanner.fragments;

import android.app.Activity;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.codepath.travelplanner.R;
import com.codepath.travelplanner.activities.MainActivity;
import com.codepath.travelplanner.apis.GooglePlacesClient;
import com.codepath.travelplanner.dialogs.BaseTripWizardDialog.OnNewTripListener;
import com.codepath.travelplanner.directions.Routing;
import com.codepath.travelplanner.directions.RoutingListener;
import com.codepath.travelplanner.directions.Segment;
import com.codepath.travelplanner.helpers.Util;
import com.codepath.travelplanner.models.GooglePlace;
import com.codepath.travelplanner.models.Trip;
import com.codepath.travelplanner.models.TripLocation;
import com.codepath.travelplanner.models.YelpFilterRequest;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.OnMapLongClickListener;
import com.google.android.gms.maps.GoogleMap.OnMarkerClickListener;
import com.google.android.gms.maps.GoogleMap.OnMyLocationChangeListener;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.model.*;
import natemobiles.app.simpleyelpapiforandroid.interfaces.IRequestListener;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * MyMapFragment - custom map fragment
 */
public class MyMapFragment extends MapFragment implements RoutingListener, IRequestListener, GoogleMap.OnCameraChangeListener {
	/** color of the multi circles */
	private static final String MULTI_CIRCLE_COLOR = "#40E6E600";
	/** map of long/lat to circle object for the multi circles */
	protected HashMap<String, Circle> coordToCircles = new HashMap<String, Circle>();
	protected ArrayList<Marker> suggestedPlaces = new ArrayList<Marker>();
	protected ArrayList<Polyline> polylines = new ArrayList<Polyline>();
	
    protected ArrayList<TripLocation> currentlyRouting;
    protected int currentNode;
	protected TripLocation start;
	protected TripLocation end;
	protected int totalDuration;
	protected ArrayList<Marker> routeMarkers = new ArrayList<Marker>();
	
	protected ArrayList<TripLocation> suggPlacesList;

	protected MapListener mapListener;

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		mapListener = (MapListener) activity;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View mapView = super.onCreateView(inflater, container, savedInstanceState);
	
		getMap().getUiSettings().setRotateGesturesEnabled(false);
		getMap().getUiSettings().setZoomControlsEnabled(false);
		getMap().setMyLocationEnabled(true);
		getMap().setOnMyLocationChangeListener(new OnMyLocationChangeListener() {
			@Override
			public void onMyLocationChange(Location location) {
				CameraUpdate center = CameraUpdateFactory.newLatLng(new LatLng(location.getLatitude(), location.getLongitude()));
				CameraUpdate zoom = CameraUpdateFactory.zoomTo(15);

				getMap().moveCamera(zoom);
				getMap().moveCamera(center);
				getMap().setOnMyLocationChangeListener(null);
			}
		});
		getMap().setOnMapClickListener(new GoogleMap.OnMapClickListener() {
			@Override
			public void onMapClick(LatLng latLng) {
				mapListener.onMapClick();
			}
		});
		return mapView;
	}

	@Override
	public void onCameraChange(CameraPosition cameraPosition) {
		// redo the search for subway stations around the area when camera position has changed
		searchNearbySubwayStations(cameraPosition.target);
	}

	/**
	 * Searches the Google Places API for the nearby subway stations at some location
	 * @param location		location to perform the search
	 */
	protected void searchNearbySubwayStations(LatLng location) {
		(new GooglePlacesClient()).search(GooglePlacesClient.SUBWAY_STATION_QUERY, location.latitude, location.longitude, this);
	}

	/** Creates a polyline on the map */
	protected void createPolyline(PolylineOptions mPolyOptions) {
		PolylineOptions polyoptions = new PolylineOptions();
		polyoptions.color(Color.BLUE);
		polyoptions.width(10);
		polyoptions.addAll(mPolyOptions.getPoints());
		polylines.add(getMap().addPolyline(polyoptions));
	}
	
	/** Removes all suggested places from the map */
	public void clearPolylines() {
		for (Polyline pLine : polylines) {
			pLine.remove();
		}
		polylines.clear();
	}
	
	/** Creates a marker on the map */
	public Marker createMarker(LatLng start, int drawable, String title, String details) {
		MarkerOptions options = new MarkerOptions();
		options.position(start);
		options.icon(BitmapDescriptorFactory.fromResource(drawable));
		options.title(title);
		options.snippet(details);
		return getMap().addMarker(options);
	}

	/** Adds a circle to the map but do not remove previous circles of this type */
	public void addMultiCircle(LatLng center, double radiusInMeters) {
		CircleOptions options = new CircleOptions();
		options.center(center);
		options.radius(radiusInMeters);
		options.strokeWidth(2);
		options.strokeColor(Color.parseColor(MULTI_CIRCLE_COLOR));
		options.fillColor(Color.parseColor(MULTI_CIRCLE_COLOR));
		coordToCircles.put(center.latitude + "," + center.longitude, getMap().addCircle(options));
	}

	/** Removes all 'multi' circles on the map */
	public void removeAllMultiCircles() {
		for (Circle circle : coordToCircles.values()) {
			circle.remove();
		}
		coordToCircles.clear();
	}
	
	/** Removes all suggested places from the map */
	public void clearSuggestedPlaces() {
		for (Marker marker : suggestedPlaces) {
			marker.remove();
		}
		suggestedPlaces.clear();
	}
	
	/** Removes all route markers from the map */
	public void clearRouteMarkers() {
		for (Marker marker : routeMarkers) {
			marker.remove();
		}
		routeMarkers.clear();
	}
	
	/** Generate new directions */
	public void newRoute(Trip trip) {
		currentlyRouting = trip.getPlaces();
		if (currentlyRouting.size() > 1) {
			clearPolylines();
			clearRouteMarkers();
			MainActivity.segments = new ArrayList<Segment>();
			getMap().setOnMapLongClickListener(null);
			totalDuration = 0;
			currentNode = 1;
			nextRoute();
		}
	}
	
	protected boolean nextRoute() {
		if(currentNode == currentlyRouting.size()) {
			return false;
		}
		
		start = currentlyRouting.get(currentNode - 1);
		end = currentlyRouting.get(currentNode);
		
		Routing routing = new Routing(Routing.TravelMode.TRANSIT);
		routing.registerListener(this);
		routing.execute(start.getLatLng(), end.getLatLng());
		return true;
	}
	
	@Override
	public void onRoutingFailure() {
		// The Routing request failed
	}

	@Override
	public void onRoutingStart() {
    	// The Routing Request starts
	}

	@Override
	public void onRoutingSuccess(PolylineOptions mPolyOptions, List<Segment> segments, int duration) {
		if (start != null && end != null) {
			//TODO: Jeff: store this is in the trip object later
			MainActivity.segments.addAll(segments);
			createPolyline(mPolyOptions);
			totalDuration += duration;
			
			routeMarkers.add(createMarker(end.getLatLng(), R.drawable.end_green, end.getLocationName(), end.getMarkerDescription()));
			
			currentNode++;
			if(!nextRoute()) {
				CameraUpdate center = CameraUpdateFactory.newLatLng(start.getLatLng());
				CameraUpdate zoom = CameraUpdateFactory.zoomTo(15);
	
				getMap().moveCamera(zoom);
				getMap().moveCamera(center);
				
				getMap().setOnMapLongClickListener(new OnMapLongClickListener() {
		            @Override
		            public void onMapLongClick(LatLng point) {
		            	OnNewTripListener listener = (OnNewTripListener) getActivity();
						listener.openAddDialog(point);
		            }
		        });
	
				mapListener.onRouted(Util.getFormattedDuration(totalDuration));
			}
		}
	}
	
	public void enterMapSelectionMode(ArrayList<TripLocation> suggPlaces, boolean newTrip) {
		suggPlacesList = suggPlaces;
		removeAllMultiCircles();
		getMap().setOnMapLongClickListener(null);
		clearSuggestedPlaces();
		for(int i = 0; i < suggPlacesList.size(); i++) {
			TripLocation toAdd = suggPlacesList.get(i);
			toAdd.setLatLng(Util.getLatLngFromAddress(toAdd.getAddress().toString(), getActivity()));
			MarkerOptions options = new MarkerOptions();
			options.position(suggPlacesList.get(i).getLatLng());
			options.title(Integer.toString(i));
			suggestedPlaces.add(getMap().addMarker(options));
		}
		getMap().setOnMarkerClickListener(new OnMarkerClickListener() {
			@Override
			public boolean onMarkerClick(Marker selected) {
				try {
					// try to show confirm route dialog
					int index = Integer.parseInt(selected.getTitle());
					TripLocation tripLocation = suggPlacesList.get(index);
					mapListener.onMarkerClick(tripLocation);
					return true;
				} catch (Exception e) {
					// do default behavior
					return false;
				}
			}
		});
		
		CameraUpdate zoom;
		if(newTrip) {
			zoom = CameraUpdateFactory.zoomTo(12);
		} else {
			zoom = CameraUpdateFactory.zoomTo(15);
		}
		getMap().setOnCameraChangeListener(this);
		getMap().animateCamera(zoom);
	}
	
	public void exitMapSelectionMode() {
		suggPlacesList = null;
		clearSuggestedPlaces();
		removeAllMultiCircles();
		getMap().setOnMarkerClickListener(null);
		getMap().setOnMapLongClickListener(new OnMapLongClickListener() {
			@Override
			public void onMapLongClick(LatLng point) {
				OnNewTripListener listener = (OnNewTripListener) getActivity();
				listener.openAddDialog(point);
			}
		});
	}

	@Override
	public void onSuccess(JSONObject successResult) {
		// on successful subway station query
		try {
			JSONArray results = successResult.getJSONArray("results");
			for (int i = 0; i < results.length(); i++) {
				GooglePlace place = GooglePlace.fromJSONObject((JSONObject) results.get(i));
				if (coordToCircles.get(place.getLatitude()+","+place.getLongitude()) == null) {
					// only add circle if it's at a new place
					LatLng latLng = new LatLng(place.getLatitude(), place.getLongitude());
					addMultiCircle(latLng, YelpFilterRequest.DEFAULT_ONE_MILE_RADIUS_IN_METER/4);
					createMarker(latLng, R.drawable.ic_subway, place.getName(), "");
				}
			}
		} catch (JSONException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onFailure(JSONObject failureResult) {
		// TODO Auto-generated method stub
	}

	/**
	 * MapListener - Interface for listening to events related to map fragment
	 */
	public interface MapListener {
		/**
		 * Called after a route query returns successfully
		 * @param durationString	string to display for the duration of the route
		 */
		public void onRouted(String durationString);

		/**
		 * Callback for when map fragment is clicked
		 */
		public void onMapClick();

		/**
		 * Callback for when a marker is clicked
		 * @param tripLocation	trip loaction of the marker
		 */
		public void onMarkerClick(TripLocation tripLocation);
	}
}
