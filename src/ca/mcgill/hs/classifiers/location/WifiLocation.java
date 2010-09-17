package ca.mcgill.hs.classifiers.location;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

/**
 * A location characterized by observable wifi base stations and the measured
 * signal strength corresponding to each base station. Distance is computed by
 * measuring the difference between signal strengths for base stations that are
 * common to both locations, with a requirement that the locations have a
 * substantial fraction of the base stations in common.
 */
public class WifiLocation extends Location {

	/** Epsilon from the paper, maximum distance between neighbouring points. */
	public static final double EPS = 6.0;

	private int num_observations = -1;

	/**
	 * ETA is the percentage of WAPs that must be shared for two observations to
	 * have a finite distance between them.
	 */
	public static final double ETA = 0.8;

	private static final String TAG = "WifiLocation";

	/**
	 * Distance threshold for merging two locations.
	 */
	public static final double MERGE_DIST = 3.0;

	public WifiLocation(final SQLiteDatabase db, final double timestamp) {
		super(db, timestamp);
	}

	/**
	 * A mapping from WAP IDs to the sums signal strengths associated with this
	 * observation.
	 */
	// private HashMap<Integer,Integer> wapStrengths = new
	// HashMap<Integer,Integer>();

	public WifiLocation(final SQLiteDatabase db, final int id) {
		super(db, id);
	}

	/**
	 * Adds a generic WiFi observation to this observation's list. The signal
	 * strength of the observation is added to this observation's mapping.
	 * 
	 * @param observation
	 *            The WiFi observation to be added.
	 */
	@Override
	public void addObservation(final Observation o) {
		final WifiObservation observation = (WifiObservation) o;
		final int my_id = getId();
		final SQLiteStatement ensureExistsStmt = db
				.compileStatement("INSERT OR IGNORE INTO "
						+ WifiLocationSet.OBSERVATIONS_TABLE
						+ " VALUES (?,?,0,0,0)");
		ensureExistsStmt.bindLong(1, my_id);
		final SQLiteStatement updateStrengthStmt = db
				.compileStatement("UPDATE "
						+ WifiLocationSet.OBSERVATIONS_TABLE
						+ " SET strength=strength+?, count=count+1, average_strength=(strength+?)/(count+1) WHERE location_id=? AND wap_id=?");
		updateStrengthStmt.bindLong(3, my_id);
		// DebugHelper.out.println("Here with " + observation.num_observations
		// + " observations.");
		try {
			db.beginTransaction();
			for (final Entry<Integer, Integer> entry : observation.measurements
					.entrySet()) {
				final int wap_id = entry.getKey();
				ensureExistsStmt.bindLong(2, wap_id);
				ensureExistsStmt.execute();
			}

			for (final Entry<Integer, Integer> entry : observation.measurements
					.entrySet()) {
				final int strength = entry.getValue();
				final int wap_id = entry.getKey();
				if (Math.abs(strength) > 100) {
					Log.d(TAG, "SIGNAL STRENGTH GREATER THAN 100!!!");
				}
				updateStrengthStmt.bindLong(1, strength);
				updateStrengthStmt.bindLong(2, strength);
				updateStrengthStmt.bindLong(4, wap_id);
				updateStrengthStmt.execute();
			}
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
			ensureExistsStmt.close();
			updateStrengthStmt.close();
		}
		// Invalidate any cached distances
		updateNumObservations();
	}

	@Override
	public double distanceFrom(final Location other) {
		final WifiLocation location = (WifiLocation) other;
		double dist = 0.0;
		int num_common = 0;

		final Cursor cursor = db.rawQuery(
				"SELECT o1.average_strength,o2.average_strength FROM "
						+ WifiLocationSet.OBSERVATIONS_TABLE + " AS o1 "
						+ "JOIN " + WifiLocationSet.OBSERVATIONS_TABLE
						+ " AS o2 USING (wap_id) "
						+ "WHERE o1.location_id=? AND o2.location_id=?",
				new String[] { Integer.toString(this.getId()),
						Integer.toString(location.getId()) });
		try {
			while (cursor.moveToNext()) {
				final double part = cursor.getDouble(0) - cursor.getDouble(1);
				dist += part * part;
				num_common += 1;
			}
		} finally {
			cursor.close();
		}

		if ((double) num_common
				/ (double) Math.min(this.numObservations(), location
						.numObservations()) < ETA) {
			dist = Double.POSITIVE_INFINITY;
		} else {
			dist = Math.sqrt((1.0 / num_common) * dist);
		}
		return dist;
	}

	/**
	 * Returns the average strength of this observation at the specified WAP ID.
	 * 
	 * @param wapID
	 *            The WAP ID to examine.
	 * @return The average strength of this observation at the specified WAP ID.
	 */
	public double getAvgStrength(final int wap_id) {
		double avgStrength = 0.0;
		final Cursor cursor = db.rawQuery("SELECT average_strength FROM "
				+ WifiLocationSet.OBSERVATIONS_TABLE
				+ " WHERE location_id=? AND wap_id=?", new String[] {
				Integer.toString(getId()), Integer.toString(wap_id) });
		try {
			cursor.moveToNext();
			avgStrength = cursor.getDouble(0);
		} finally {
			cursor.close();
		}
		return avgStrength;
	}

	public Set<Integer> getObservableWAPs() {
		final Set<Integer> wap_ids = new HashSet<Integer>();
		final Cursor cursor = db.rawQuery("SELECT wap_id FROM "
				+ WifiLocationSet.OBSERVATIONS_TABLE + " WHERE location_id=?",
				new String[] { Integer.toString(getId()) });
		try {
			while (cursor.moveToNext()) {
				wap_ids.add(cursor.getInt(0));
			}
		} finally {
			cursor.close();
		}
		return wap_ids;
	}

	@Override
	public Observation getObservations() {
		final WifiObservation observation = new WifiObservation(getTimestamp(),
				35);
		final Cursor cursor = db.rawQuery(
				"SELECT wap_id,average_strength FROM "
						+ WifiLocationSet.OBSERVATIONS_TABLE
						+ " WHERE location_id=?", new String[] { Integer
						.toString(getId()) });
		try {
			while (cursor.moveToNext()) {
				observation.addObservation(cursor.getInt(0), cursor.getInt(1));
			}
		} finally {
			cursor.close();
		}
		return observation;
	}

	public int numObservations() {
		if (num_observations < 0) {
			updateNumObservations();
		}
		return num_observations;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append("Location Id: " + getId() + "\n");
		sb.append("\tWAPS:\n");
		final Set<Integer> wap_ids = getObservableWAPs();
		for (final int wap_id : wap_ids) {
			sb.append("\t\tId: " + wap_id + "\tAverage Strength: "
					+ getAvgStrength(wap_id) + "\n");
		}
		sb.append("\tNeighbours:");
		final List<Integer> neighbours = getNeighbours();
		for (final int neighbour : neighbours) {
			sb.append(" " + neighbour);
		}
		sb.append("\n");
		return sb.toString();
	}

	private void updateNumObservations() {
		final Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM "
				+ WifiLocationSet.OBSERVATIONS_TABLE + " WHERE location_id=?",
				new String[] { new Integer(getId()).toString() });
		try {
			cursor.moveToNext();
			num_observations = cursor.getInt(0);
		} finally {
			cursor.close();
		}
	}
}
