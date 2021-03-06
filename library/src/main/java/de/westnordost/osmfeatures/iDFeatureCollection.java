package de.westnordost.osmfeatures;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class iDFeatureCollection implements FeatureCollection
{
	private static final String FEATURES_FILE = "presets.json";

	public interface FileAccessAdapter
	{
		boolean exists(String name) throws IOException;
		InputStream open(String name) throws IOException;
	}

	private final FileAccessAdapter fileAccess;

	// id -> feature
	private final Map<String, Feature> allFeatures;
	// locale -> ( id -> feature )
	private final Map<Locale, Map<String, Feature>> localizedFeatures = new HashMap<>();

	iDFeatureCollection(FileAccessAdapter fileAccess)
	{
		this.fileAccess = fileAccess;
		allFeatures = loadFeatures();
		// already load localization for default locale
		getOrLoadLocalizedFeatures(Locale.getDefault());
	}

	@Override public Collection<Feature> getAll(Locale locale)
	{
		if(locale == null) return Collections.unmodifiableCollection(allFeatures.values());
		return Collections.unmodifiableCollection(getOrLoadLocalizedFeaturesWithFallbackToDefault(locale).values());
	}

	@Override public Feature get(String id, Locale locale)
	{
		if(locale == null) return allFeatures.get(id);
		return getOrLoadLocalizedFeaturesWithFallbackToDefault(locale).get(id);
	}

	private Map<String, Feature> loadFeatures()
	{
		try(InputStream is = fileAccess.open(FEATURES_FILE)) { return parseFeatures(is); }
		catch (IOException | JSONException e) { throw new RuntimeException(e); }
	}

	private static Map<String, Feature> parseFeatures(InputStream is) throws JSONException, IOException
	{
		JSONObject object = createFromInputStream(is);
		Map<String, Feature> result = new HashMap<>();
		JSONObject presetObjects = object.getJSONObject("presets");
		for (Iterator<String> it = presetObjects.keys(); it.hasNext(); )
		{
			String id = it.next();
			JSONObject p = presetObjects.getJSONObject(id);
			Map<String,String> tags = parseStringMap(p.getJSONObject("tags"));
			// drop features with * in key or value of tags (for now), because they never describe
			// a concrete thing, but some category of things.
			// TODO maybe drop this limitation
			if(anyKeyOrValueContainsWildcard(tags)) continue;
			// also dropping features with empty tags (generic point, line, relation)
			if(tags.isEmpty()) continue;

			List<GeometryType> geometry = parseList(p.getJSONArray("geometry"),
					item -> GeometryType.valueOf(((String)item).toUpperCase(Locale.US)));
			boolean suggestion = p.optBoolean("suggestion", false);
			String name = p.getString("name");
			List<String> terms = parseCommaSeparatedList(p.optString("terms"), name);
			List<String> countryCodes = parseList(p.optJSONArray("countryCodes"),
					item -> ((String)item).toUpperCase(Locale.US).intern());
			boolean searchable = p.optBoolean("searchable", true);
			double matchScore = p.optDouble("matchScore", 1.0);
			Map<String,String> addTags = parseStringMap(p.optJSONObject("addTags"));

			result.put(id.intern(), new Feature(
					id.intern(), tags, geometry, name, terms, countryCodes, searchable, matchScore,
					suggestion, addTags
			));
		}
		return result;
	}

	private static boolean anyKeyOrValueContainsWildcard(Map<String,String> map)
	{
		for (Map.Entry<String, String> e : map.entrySet())
		{
			if(e.getKey().contains("*") || e.getValue().contains("*")) return true;
		}
		return false;
	}

	private interface Transformer<T> { T apply(Object item); }
	private static <T> List<T> parseList(JSONArray array, Transformer<T> t) throws JSONException
	{
		if(array == null) return Collections.emptyList();
		List<T> result = new ArrayList<>(array.length());
		for (int i = 0; i < array.length(); i++)
		{
			T item = t.apply(array.get(i));
			if(item != null) result.add(item);
		}
		return Collections.unmodifiableList(result);
	}

	private static List<String> parseCommaSeparatedList(String str, String remove)
	{
		if(str == null || str.isEmpty()) return Collections.emptyList();
		String[] array = str.split("\\s*,\\s*");
		List<String> result = new ArrayList<>(array.length);
		Collections.addAll(result, array);
		result.remove(remove);
		return Collections.unmodifiableList(result);
	}

	private static Map<String, String> parseStringMap(JSONObject map) throws JSONException
	{
		if(map == null) return Collections.emptyMap();
		Map<String, String> result = new HashMap<>(map.length());
		for (Iterator<String> it = map.keys(); it.hasNext(); )
		{
			String key = it.next().intern();
			result.put(key, map.getString(key));
		}
		return Collections.unmodifiableMap(result);
	}

	private Map<String, Feature> getOrLoadLocalizedFeaturesWithFallbackToDefault(Locale locale)
	{
		Map<String, Feature> result = getOrLoadLocalizedFeatures(locale);
		if(result == null)
		{
			Locale localeLanguage = new Locale(locale.getLanguage());
			result = getOrLoadLocalizedFeatures(localeLanguage);
		}
		if(result == null) result = allFeatures;
		return result;
	}

	private Map<String, Feature> getOrLoadLocalizedFeatures(Locale locale)
	{
		synchronized (localizedFeatures)
		{
			if (!localizedFeatures.containsKey(locale))
			{
				Locale localeLanguage = new Locale(locale.getLanguage());
				if (!localizedFeatures.containsKey(localeLanguage))
				{
					localizedFeatures.put(localeLanguage, mergeFeatures(
							loadLocalizedFeatures(localeLanguage),
							allFeatures
					));
				}
				// merging language features with country features (country features overwrite language features)
				if(!locale.getCountry().isEmpty())
				{
					Map<String, Feature> baseFeatures = localizedFeatures.get(localeLanguage);
					if(baseFeatures == null) baseFeatures = allFeatures;

					localizedFeatures.put(locale, mergeFeatures(
						loadLocalizedFeatures(locale),
						baseFeatures
					));
				}
			}
			return localizedFeatures.get(locale);
		}
	}

	private static Map<String, Feature> mergeFeatures(Map<String, Feature> features, Map<String, Feature> baseFeatures)
	{
		if(features != null && baseFeatures != null)
		{
			for (String id : baseFeatures.keySet())
			{
				if (!features.containsKey(id)) features.put(id, baseFeatures.get(id));
			}
		}
		return features;
	}

	private Map<String, Feature> loadLocalizedFeatures(Locale locale)
	{
		String lang = locale.getLanguage();
		String country = locale.getCountry();
		String filename = lang + (country.length() > 0 ? "-" + country : "") + ".json";
		try
		{
			if (!fileAccess.exists(filename)) return null;
			try (InputStream is = fileAccess.open(filename))
			{
				return parseLocalizedFeatures(is);
			}
		}
		catch (IOException | JSONException e) { throw new RuntimeException(e); }
	}

	private Map<String, Feature> parseLocalizedFeatures(InputStream is) throws JSONException, IOException
	{
		JSONObject object = createFromInputStream(is);
		Map<String, Feature> result = new HashMap<>(allFeatures.size());
		JSONObject presetsObject = object.getJSONObject("presets");
		for (Iterator<String> it = presetsObject.keys(); it.hasNext(); )
		{
			String id = it.next();
			id = id.intern();
			Feature baseFeatures = allFeatures.get(id);
			if(baseFeatures == null) continue;

			JSONObject localization = presetsObject.getJSONObject(id);
			String name = localization.optString("name");
			if(name == null || name.isEmpty()) continue;
			List<String> terms = parseCommaSeparatedList(localization.optString("terms"), name);
			result.put(id, new Feature(baseFeatures, name, terms));
		}
		return result;
	}

	// this is only necessary because Android uses some old version of org.json where
	// new JSONObject(new JSONTokener(inputStream)) is not defined...
	private static JSONObject createFromInputStream(InputStream inputStream) throws IOException
	{
		ByteArrayOutputStream result = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		int length;
		while ((length = inputStream.read(buffer)) != -1) {
			result.write(buffer, 0, length);
		}
		String jsonString = result.toString("UTF-8");
		return new JSONObject(jsonString);
	}
}
