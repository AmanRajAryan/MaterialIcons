package aman.icons;

import aman.icons.Logging.Log;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.caverock.androidsvg.SVG;
import java.util.Set;
import java.util.HashSet;
import okhttp3.*;
import org.json.JSONArray; // Added
import org.json.JSONObject; // Added
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ICON_HUNTER";
    private static final String PREFS_NAME = "IconHunterPrefs";
    private static final String KEY_DOWNLOAD_DIR = "download_tree_uri";

    // 1. NEW DATA SOURCE: Contains "tags" (synonyms) for every icon
    private static final String ICON_LIST_URL =
            "https://fonts.google.com/metadata/icons?key=material_symbols&incomplete=true";

    private static final String ICON_BASE_URL =
            "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/%s/default/24px.svg";

    private EditText searchBar;
    private ImageButton btnSelectFolder;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;

    private List<IconModel> allIcons = new ArrayList<>();
    private IconAdapter adapter;
    private OkHttpClient client = new OkHttpClient();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService diskExecutor = Executors.newSingleThreadExecutor();
    private LruCache<String, Bitmap> memoryCache;

    private Uri customDownloadUri = null;
    private ActivityResultLauncher<Intent> folderPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initCache();
        restoreDownloadLocation();
        setupFolderPicker();

        searchBar = findViewById(R.id.searchBar);
        btnSelectFolder = findViewById(R.id.btnSelectFolder);
        recyclerView = findViewById(R.id.recyclerView);
        progressBar = findViewById(R.id.progressBar);

        recyclerView.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new IconAdapter();
        recyclerView.setAdapter(adapter);

        searchBar.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int c, int a) {}

                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        filterIcons(s.toString());
                    }

                    public void afterTextChanged(Editable s) {}
                });

        btnSelectFolder.setOnClickListener(v -> openFolderPicker());

        fetchIconList();
    }

    private void initCache() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        memoryCache =
                new LruCache<String, Bitmap>(cacheSize) {
                    @Override
                    protected int sizeOf(String key, Bitmap bitmap) {
                        return bitmap.getByteCount() / 1024;
                    }
                };
    }

    // --- SMART SEARCH FETCHING ---
    private void fetchIconList() {
        // Check for cached list first (Metadata JSON is ~3MB, so caching is good)
        File cacheFile = new File(getCacheDir(), "icons_metadata.json");
        if (cacheFile.exists()) {
            diskExecutor.execute(
                    () -> {
                        try (FileInputStream in = new FileInputStream(cacheFile)) {
                            byte[] buffer = new byte[(int) cacheFile.length()];
                            in.read(buffer);
                            String jsonStr = new String(buffer, StandardCharsets.UTF_8);
                            parseMetadataJson(jsonStr);
                        } catch (Exception e) {
                            // If cache fails, fetch from network
                            fetchFromNetwork();
                        }
                    });
        } else {
            fetchFromNetwork();
        }
    }

    private void fetchFromNetwork() {
        Request request = new Request.Builder().url(ICON_LIST_URL).build();
        client.newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                mainHandler.post(
                                        () -> {
                                            Toast.makeText(
                                                            MainActivity.this,
                                                            "Network Error",
                                                            Toast.LENGTH_SHORT)
                                                    .show();
                                            progressBar.setVisibility(View.GONE);
                                        });
                            }

                            @Override
                            public void onResponse(Call call, Response response)
                                    throws IOException {
                                if (response.isSuccessful()) {
                                    String jsonStr = response.body().string();

                                    // Save to cache for next time
                                    try (FileOutputStream out =
                                            new FileOutputStream(
                                                    new File(
                                                            getCacheDir(),
                                                            "icons_metadata.json"))) {
                                        out.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                                    } catch (Exception ignored) {
                                    }

                                    parseMetadataJson(jsonStr);
                                }
                            }
                        });
    }

    // --- 2. NEW PARSER (Extracts Tags) ---
    private void parseMetadataJson(String jsonStr) {
        try {
            // Google's API sometimes adds a security prefix ")]}'". We must remove it.
            if (jsonStr.startsWith(")]}'")) {
                jsonStr = jsonStr.substring(jsonStr.indexOf("\n") + 1);
            }

            JSONObject root = new JSONObject(jsonStr);
            JSONArray iconsArray = root.getJSONArray("icons");

            List<IconModel> parsedList = new ArrayList<>();

            for (int i = 0; i < iconsArray.length(); i++) {
                JSONObject item = iconsArray.getJSONObject(i);
                String name = item.getString("name");

                // Extract Tags
                List<String> tagsList = new ArrayList<>();
                if (item.has("tags")) {
                    JSONArray tagsJson = item.getJSONArray("tags");
                    for (int j = 0; j < tagsJson.length(); j++) {
                        tagsList.add(tagsJson.getString(j).toLowerCase());
                    }
                }

                parsedList.add(new IconModel(name, tagsList));
            }

            mainHandler.post(
                    () -> {
                        allIcons = parsedList;
                        adapter.updateList(allIcons);
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(
                                        MainActivity.this,
                                        "Loaded " + allIcons.size() + " Smart Icons",
                                        Toast.LENGTH_SHORT)
                                .show();
                    });

        } catch (Exception e) {
            e.printStackTrace();
            mainHandler.post(
                    () ->
                            Toast.makeText(MainActivity.this, "Parse Error", Toast.LENGTH_SHORT)
                                    .show());
        }
    }

    // --- 3. UPDATED FILTER (Checks Tags) ---
    private void filterIcons(String query) {
        String q = query.toLowerCase().trim();

        if (q.isEmpty()) {
            adapter.updateList(allIcons);
            return;
        }

        // 1. Create Buckets
        List<IconModel> exactMatches = new ArrayList<>();
        List<IconModel> nameMatches = new ArrayList<>();
        List<IconModel> tagMatches = new ArrayList<>();

        // 2. Filter into buckets
        for (IconModel icon : allIcons) {
            if (icon.name.equals(q)) {
                exactMatches.add(icon);
            } else if (icon.name.contains(q)) {
                nameMatches.add(icon);
            } else {
                for (String tag : icon.tags) {
                    if (tag.contains(q)) {
                        tagMatches.add(icon);
                        break;
                    }
                }
            }
        }

        // 3. Merge & Deduplicate
        // Use a Set to track names we've already added
        Set<String> addedNames = new HashSet<>();
        List<IconModel> finalResult = new ArrayList<>();

        // Helper to add only unique items
        addUnique(exactMatches, finalResult, addedNames);
        addUnique(nameMatches, finalResult, addedNames);
        addUnique(tagMatches, finalResult, addedNames);

        adapter.updateList(finalResult);
    }

    // Helper method to keep code clean
    private void addUnique(List<IconModel> source, List<IconModel> destination, Set<String> seen) {
        for (IconModel icon : source) {
            // set.add() returns true only if the item was NOT already in the set
            if (seen.add(icon.name)) {
                destination.add(icon);
            }
        }
    }

    // --- IMAGE LOADING & CACHING (Dual Cache) ---
    // [This part remains unchanged from the previous efficient version]

    private void saveBitmapToDisk(String url, Bitmap bitmap) {
        diskExecutor.execute(
                () -> {
                    try {
                        File file =
                                new File(getCacheDir(), String.valueOf(url.hashCode()) + ".png");
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                    } catch (Exception e) {
                    }
                });
    }

    private Bitmap loadBitmapFromDisk(String url) {
        File file = new File(getCacheDir(), String.valueOf(url.hashCode()) + ".png");
        return file.exists() ? BitmapFactory.decodeFile(file.getAbsolutePath()) : null;
    }

    private void saveRawSvgToDisk(String url, String svgContent) {
        diskExecutor.execute(
                () -> {
                    try {
                        File file =
                                new File(getCacheDir(), String.valueOf(url.hashCode()) + ".svg");
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            out.write(svgContent.getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (Exception e) {
                    }
                });
    }

    private String loadRawSvgFromDisk(String url) {
        File file = new File(getCacheDir(), String.valueOf(url.hashCode()) + ".svg");
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[(int) file.length()];
                in.read(buffer);
                return new String(buffer, StandardCharsets.UTF_8);
            } catch (IOException e) {
            }
        }
        return null;
    }

    private void loadSvgPreview(ImageView imageView, IconModel icon) {
        String url = String.format(ICON_BASE_URL, icon.name);
        imageView.setTag(url);
        imageView.setImageResource(android.R.drawable.ic_menu_help);
        imageView.setVisibility(View.VISIBLE);

        Bitmap memoryBitmap = memoryCache.get(url);
        if (memoryBitmap != null) {
            imageView.setImageBitmap(memoryBitmap);
            imageView.setColorFilter(0xFFFFFFFF);
            return;
        }

        diskExecutor.execute(
                () -> {
                    Bitmap diskBitmap = loadBitmapFromDisk(url);
                    if (diskBitmap != null) {
                        memoryCache.put(url, diskBitmap);
                        mainHandler.post(
                                () -> {
                                    if (url.equals(imageView.getTag())) {
                                        imageView.setImageBitmap(diskBitmap);
                                        imageView.setColorFilter(0xFFFFFFFF);
                                    }
                                });
                        return;
                    }

                    String localSvg = loadRawSvgFromDisk(url);
                    if (localSvg != null) {
                        renderSvgAndCache(url, localSvg, imageView);
                        return;
                    }

                    Request request = new Request.Builder().url(url).build();
                    client.newCall(request)
                            .enqueue(
                                    new Callback() {
                                        @Override
                                        public void onFailure(Call call, IOException e) {
                                            mainHandler.post(
                                                    () -> imageView.setVisibility(View.INVISIBLE));
                                        }

                                        @Override
                                        public void onResponse(Call call, Response response)
                                                throws IOException {
                                            if (response.isSuccessful()) {
                                                String rawSvg = response.body().string();
                                                saveRawSvgToDisk(url, rawSvg);
                                                renderSvgAndCache(url, rawSvg, imageView);
                                            } else {
                                                mainHandler.post(
                                                        () ->
                                                                imageView.setVisibility(
                                                                        View.INVISIBLE));
                                            }
                                        }
                                    });
                });
    }

    private void renderSvgAndCache(String url, String svgString, ImageView imageView) {
        try {
            SVG svg = SVG.getFromString(svgString);
            int size = 96;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            svg.setDocumentWidth(size);
            svg.setDocumentHeight(size);
            svg.renderToCanvas(canvas);

            memoryCache.put(url, bitmap);
            saveBitmapToDisk(url, bitmap);

            mainHandler.post(
                    () -> {
                        if (url.equals(imageView.getTag())) {
                            imageView.setImageBitmap(bitmap);
                            imageView.setColorFilter(0xFFFFFFFF);
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processIcon(IconModel icon, int mode) {
        String url = String.format(ICON_BASE_URL, icon.name);
        String toastMsg = mode == 0 ? "Processing Copy..." : "Processing Save...";
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();

        diskExecutor.execute(
                () -> {
                    String svgContent = loadRawSvgFromDisk(url);
                    if (svgContent != null) {
                        extractAndProcess(icon.name, svgContent, mode);
                    } else {
                        client.newCall(new Request.Builder().url(url).build())
                                .enqueue(
                                        new Callback() {
                                            @Override
                                            public void onFailure(Call call, IOException e) {
                                                mainHandler.post(
                                                        () ->
                                                                Toast.makeText(
                                                                                MainActivity.this,
                                                                                "Network Error",
                                                                                Toast.LENGTH_SHORT)
                                                                        .show());
                                            }

                                            @Override
                                            public void onResponse(Call call, Response response)
                                                    throws IOException {
                                                if (response.isSuccessful()) {
                                                    String netSvg = response.body().string();
                                                    saveRawSvgToDisk(url, netSvg);
                                                    extractAndProcess(icon.name, netSvg, mode);
                                                }
                                            }
                                        });
                    }
                });
    }

    private void extractAndProcess(String name, String svgContent, int mode) {
        String pathData = "";
        try {
            int start = svgContent.indexOf("d=\"") + 3;
            int end = svgContent.indexOf("\"", start);
            if (start > 3 && end > start) pathData = svgContent.substring(start, end);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // FIX: Use 960 viewport and shift Y-axis by 960
        String xmlOutput =
                "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    android:width=\"24dp\"\n"
                        + "    android:height=\"24dp\"\n"
                        + "    android:viewportWidth=\"960\"\n"
                        + // Changed from 24 to 960
                        "    android:viewportHeight=\"960\"\n"
                        + // Changed from 24 to 960
                        "    android:tint=\"#FFFFFF\">\n"
                        + "    <group android:translateY=\"960\">\n"
                        + // Shift content down into view
                        "        <path\n"
                        + "            android:fillColor=\"@android:color/white\"\n"
                        + "            android:pathData=\""
                        + pathData
                        + "\"/>\n"
                        + "    </group>\n"
                        + "</vector>";

        mainHandler.post(
                () -> {
                    if (mode == 0) {
                        ClipboardManager clipboard =
                                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(
                                ClipData.newPlainText("Android Vector", xmlOutput));
                        Toast.makeText(MainActivity.this, "Copied Fixed XML", Toast.LENGTH_SHORT)
                                .show();
                    } else {
                        saveXmlToFile(name, xmlOutput);
                    }
                });
    }

    // --- FOLDER & PERSISTENCE (Unchanged) ---
    private void restoreDownloadLocation() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String uriString = prefs.getString(KEY_DOWNLOAD_DIR, null);
        if (uriString != null) {
            try {
                customDownloadUri = Uri.parse(uriString);
                getContentResolver()
                        .takePersistableUriPermission(
                                customDownloadUri,
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                customDownloadUri = null;
            }
        }
    }

    private void setupFolderPicker() {
        folderPickerLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                                Uri uri = result.getData().getData();
                                if (uri != null) {
                                    getContentResolver()
                                            .takePersistableUriPermission(
                                                    uri,
                                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                                            | Intent
                                                                    .FLAG_GRANT_READ_URI_PERMISSION);
                                    SharedPreferences prefs =
                                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                                    prefs.edit()
                                            .putString(KEY_DOWNLOAD_DIR, uri.toString())
                                            .apply();
                                    customDownloadUri = uri;
                                    Toast.makeText(
                                                    this,
                                                    "Save location updated!",
                                                    Toast.LENGTH_SHORT)
                                            .show();
                                }
                            }
                        });
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        folderPickerLauncher.launch(intent);
    }

    private void saveXmlToFile(String iconName, String xmlContent) {
        String fileName = "ic_" + iconName + ".xml";
        if (customDownloadUri != null) {
            try {
                DocumentFile pickedDir = DocumentFile.fromTreeUri(this, customDownloadUri);
                if (pickedDir != null && pickedDir.exists()) {
                    DocumentFile newFile = pickedDir.createFile("text/xml", fileName);
                    if (newFile != null) {
                        try (OutputStream out =
                                getContentResolver().openOutputStream(newFile.getUri())) {
                            out.write(xmlContent.getBytes());
                            Toast.makeText(this, "Saved to Custom Folder", Toast.LENGTH_SHORT)
                                    .show();
                            return;
                        }
                    }
                }
            } catch (Exception e) {
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/xml");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri =
                        getContentResolver()
                                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        out.write(xmlContent.getBytes());
                        Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                File path =
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS);
                File file = new File(path, fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(xmlContent.getBytes());
                    Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Save Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    class IconAdapter extends RecyclerView.Adapter<IconAdapter.ViewHolder> {
        private List<IconModel> data = new ArrayList<>();

        void updateList(List<IconModel> newData) {
            this.data = newData;
            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v =
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_icon_grid, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            IconModel icon = data.get(position);
            holder.name.setText(icon.name);
            loadSvgPreview(holder.preview, icon);
            holder.btnCopy.setOnClickListener(v -> processIcon(icon, 0));
            holder.btnSave.setOnClickListener(v -> processIcon(icon, 1));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView name;
            ImageView preview;
            Button btnCopy, btnSave;

            ViewHolder(View v) {
                super(v);
                name = v.findViewById(R.id.iconName);
                preview = v.findViewById(R.id.iconPreview);
                btnCopy = v.findViewById(R.id.btnCopy);
                btnSave = v.findViewById(R.id.btnSave);
            }
        }
    }

    // --- 4. MODEL WITH TAGS ---
    class IconModel {
        String name;
        List<String> tags;

        IconModel(String n, List<String> t) {
            name = n;
            tags = t;
        }
    }
}
