package com.yuri.onscripter;

import static android.provider.DocumentsContract.Document.MIME_TYPE_DIR;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private static final int ACTIVITY_SAF = 10;
    private static final String COLOR_NOTDIR = "#FF0000";
    private static final String COLOR_INRDIR = "#4CAF50";
    private static final String COLOR_SAFDIR = "#FF26C6DA";
    private static final String COLOR_EXTDIR = "#FFD4E157";
    private static final String COLOR_SMBDIR = "#FFB39DDB";
    public static final String SHAREDPREF_NAME = "onsyuri";
    public static final String SHAREDPREF_GAMEDIR = "gamedir";
    public static final String SHAREDPREF_GAMEURI = "gameuri";
    public static final String SHAREDPREF_GAMECONFIG = "gameargs";
    // smb virtual path, the game runs on demand without a full download
    public static final String SHAREDPREF_SMB_TOKEN = "smbtoken";
    public static final String SHAREDPREF_SMB_HOST = "smbhost";
    public static final String SHAREDPREF_SMB_PORT = "smbport";
    public static final String SHAREDPREF_SMB_SHARE = "smbshare";
    public static final String SHAREDPREF_SMB_BASE = "smbbase";
    public static final String SHAREDPREF_SMB_USER = "smbuser";
    public static final String SHAREDPREF_SMB_PASSWORD = "smbpassword";

    private

    enum DIR_TYPE{
        NOT_DIR,
        NORMAL_DIR,
        SAF_DIR
    }

    class GameInfo {
        private boolean m_isIconCached;
        private Bitmap m_icon;
        public boolean m_isUri;
        public boolean m_isSmb;
        public GameSources.Source m_smbSource;
        public String m_safBaseUri;
        public String m_path;
        public String m_name = null;

        public GameInfo(String path, boolean isUri) {
            this(path, isUri, isUri ? path : null);
        }

        public GameInfo(String path, boolean isUri, String safBaseUri) {
            m_isUri = isUri;
            m_safBaseUri = safBaseUri;
            m_path = path;
            m_isIconCached = false;
            if(m_isUri){
                Uri uri =  Uri.parse(path);
                String mimeType = getContentResolver().getType(uri);
                if(mimeType != null && mimeType.equals(MIME_TYPE_DIR)) {
                   Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                    if(cursor!=null) {
                        int nameidx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        cursor.moveToFirst();
                        m_name = cursor.getString(nameidx);
                        cursor.close();
                    }
                }
            }
            else { // check if valid dir
                File file = new File(m_path);
                if(file.isDirectory())  {
                    m_name = file.getName();
                }
            }
        }

        /** a game directory on an smb source, path is relative to the share */
        public GameInfo(GameSources.Source source, String remotePath, String name) {
            m_isIconCached = false;
            m_isUri = false;
            m_isSmb = true;
            m_smbSource = source;
            m_path = remotePath;
            m_name = name;
        }

        public String getPathType(){
            if(m_isSmb) return "smb";
            if(m_isUri) return "saf";
            if(m_path.contains("emulated")) return "inr";
            else return "ext";
        }

        public String getGameDir() {
            if(m_isSmb) return m_smbSource == null ? m_path : m_smbSource.path;
            return  m_isUri ? m_name : m_path;
        }
        public String getRealPath(){
            if(m_isSmb) {
                String base;
                if(m_smbSource == null) {
                    base = "smb://";
                } else {
                    base = "smb://" + GameSources.hostPort(m_smbSource) + "/"
                            + GameSources.smbShare(m_smbSource);
                }
                if(base.endsWith("/")) return base + m_path;
                return base + "/" + m_path;
            }
            return m_isUri ? SafFile.uri2Path(m_path) : m_path;
        }

        /** stable id for the smb virtual path, also used as the on-demand cache name */
        public String getVirtualToken() {
            if (m_smbSource == null) return safeFileName(m_name);
            String id = GameSources.smbHost(m_smbSource) + "|" + m_smbSource.port + "|"
                    + GameSources.smbShare(m_smbSource) + "|" + m_path;
            return safeFileName(m_name) + "_" + Integer.toHexString(id.hashCode());
        }

        public String getVirtualRoot() {
            return SmbVirtualFs.ROOT_PREFIX + getVirtualToken();
        }

        public Bitmap getIcon(){
            if(m_isIconCached) return m_icon;
            m_isIconCached = true;
            Bitmap bitmap = null;
            if(m_isSmb) return null;
            if(m_isUri) { // append uri with %2f (`/`)
                Uri uri = Uri.parse(m_path +"%2Ficon.png");
                if(uri!=null) {
                    Context context = getApplicationContext();
                    DocumentFile doc = DocumentFile.fromSingleUri(context, uri);
                    if(doc!=null && doc.canRead()){
                        try {
                            InputStream in = context.getContentResolver().openInputStream(doc.getUri());
                            bitmap = BitmapFactory.decodeStream(in);
                        }
                        catch (FileNotFoundException e){
                            return  null;
                        }
                    }
                }
            }
            else {
                String iconpath;
                if(m_path.charAt(m_path.length()-1) == '/') iconpath = m_path + "icon.png";
                else iconpath = m_path + "/icon.png";
                bitmap = BitmapFactory.decodeFile(iconpath);
            }
            m_icon = bitmap;
            return bitmap;
        }

        public int checkValid() {
            if(m_isSmb) return 7; // it was listed as a directory, download and check later
            int res = 0;
            boolean has_script = false;
            boolean has_font = false;
            HashSet<String> ons_script = new HashSet<>();
            ons_script.add("0.txt");
            ons_script.add("00.txt");
            ons_script.add("nscr_sec.dat");
            ons_script.add("nscript.dat");
            ons_script.add("onscript.nt2");
            ons_script.add("onscript.nt3");

            if(m_isUri){
                Context context = getApplicationContext();
                Uri uri = Uri.parse(m_path);
                DocumentFile docfile = DocumentFile.fromTreeUri(context, uri);
                if(Objects.requireNonNull(docfile).canRead()) res += 4;
                if(docfile.canWrite()) res += 2;

                uri = Uri.parse(m_path +"%2Fdefault.ttf");
                docfile = DocumentFile.fromSingleUri(context, uri);
                if(docfile!=null && docfile.exists()){
                    has_font = true;
                }
                if(has_font){
                    for (String name: ons_script){
                        uri = Uri.parse(m_path +"%2F" + name);
                        docfile = DocumentFile.fromSingleUri(context, uri);
                        if(docfile!=null && docfile.exists()){
                            has_script = true;
                            break;
                        }
                    }
                }
            }
            else {
                File file = new File(m_path);
                if(file.canRead()) res += 4;
                if(file.canWrite()) res += 2;
                for(String path : Objects.requireNonNull(file.list())){
                    if(ons_script.contains(path)) has_script = true;
                    if(path.equals("default.ttf")) has_font = true;
                    if(has_script && has_font) break;
                }
            }
            if(has_script && has_font) res += 1;
            return res;
        }
    }

    class GamelistAdapter extends BaseAdapter {
        private LayoutInflater m_inflater;
        protected class ItemViewHolder { // cache view
            ImageButton button_gameicon;
            TextView text_gametitle;
            TextView text_pathtype;
            TextView text_gameno;
        }

        @Override
        public int getCount() {
            if(m_gamelist!=null) return m_gamelist.size();
            else return 0;
        }

        @Override
        public Object getItem(int position) {
            if(m_gamelist!=null) return m_gamelist.get(position);
            else return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @SuppressLint({"InflateParams", "SetTextI18n", "DefaultLocale"})
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout rootview;
            ItemViewHolder holder;
            if (convertView==null) { // cache the view
                holder=new ItemViewHolder();
                if(m_inflater==null) m_inflater = LayoutInflater.from(parent.getContext());
                rootview= (LinearLayout) m_inflater.inflate(R.layout.layout_gameinfo, null);
                holder.button_gameicon = rootview.findViewById(R.id.button_gameicon);
                holder.text_gametitle = rootview.findViewById(R.id.text_gametitle);
                holder.text_pathtype = rootview.findViewById(R.id.text_pathtype);
                holder.text_gameno = rootview.findViewById(R.id.text_gameno);
                rootview.setTag(holder);
            }
            else { // obtain from cache
                rootview=(LinearLayout)convertView;
                holder=(ItemViewHolder)convertView.getTag();
            }
            if (m_gamelist==null) return rootview;

            getGameInfoView(holder, position);
            getGameDetailView(rootview, holder, position);

            return rootview;
        }

        @SuppressLint({"SetTextI18n", "DefaultLocale"})
        private void getGameInfoView(ItemViewHolder holder, int position) {
            GameInfo gameinfo = m_gamelist.get(position);

            // gamelist title
            String shortpath = "";
            Configuration config = MainActivity.this.getResources().getConfiguration();
            if(config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                shortpath = " | " + gameinfo.getRealPath();
            }
            holder.text_gametitle.setText(" " + gameinfo.m_name + shortpath);

            // gamelist icon
            Bitmap bitmap = gameinfo.getIcon();
            if(bitmap==null) {
                bitmap = BitmapFactory.decodeResource(MainActivity.this.getResources(), R.mipmap.ic_launcher);
            }
            holder.button_gameicon.setImageBitmap(bitmap);

            // game path type
            String pathtype = gameinfo.getPathType();
            switch (pathtype){
                case "inr":
                    holder.text_pathtype.setTextColor(Color.parseColor(COLOR_INRDIR));
                    break;
                case "ext":
                    holder.text_pathtype.setTextColor(Color.parseColor(COLOR_EXTDIR));
                    break;
                case "saf":
                    holder.text_pathtype.setTextColor(Color.parseColor(COLOR_SAFDIR));
                    break;
                case "smb":
                    holder.text_pathtype.setTextColor(Color.parseColor(COLOR_SMBDIR));
                    break;
                default:
                    holder.text_pathtype.setTextColor(Color.parseColor(COLOR_NOTDIR));
                    break;
            }
            holder.text_pathtype.setText(pathtype);

            // game number
            holder.text_gameno.setText(String.format("%d/%d", position + 1,  this.getCount()));
        }

        @SuppressLint("RtlHardcoded")
        private void getGameDetailView(LinearLayout rootview, ItemViewHolder holder, int position) {
            GameInfo gameinfo = m_gamelist.get(position);

            // game run shortcut
            holder.button_gameicon.setOnClickListener(view -> {
                if (gameinfo.m_isSmb) {
                    MainActivity.this.runRemoteVirtual(gameinfo);
                    return;
                }
                int gamemod = gameinfo.checkValid();
                ArrayList<String> onsargs = prepareOnsargs(gameinfo.getGameDir());
                startOnsyuri(gamemod, gameinfo, onsargs);
            });

            // game details
            holder.text_gametitle.setOnClickListener(event->{
                if (gameinfo.m_isSmb) {
                    MainActivity.this.showRemoteGameDetail(gameinfo);
                    return;
                }
                // game name, path, icon
                int gamemod = gameinfo.checkValid();
                @SuppressLint("InflateParams")
                View view_gamedetail = LayoutInflater.from(MainActivity.this).inflate(R.layout.layout_gamedetail, null);
                TextView text_gamename = view_gamedetail.findViewById(R.id.text_gamename);
                text_gamename.setText(gameinfo.m_name);
                TextView text_gamepath = view_gamedetail.findViewById(R.id.text_gamepath);
                text_gamepath.setText(gameinfo.getRealPath());
                ImageView image_gameicon = view_gamedetail.findViewById(R.id.image_gameicon);
                image_gameicon.setImageBitmap(((BitmapDrawable)holder.button_gameicon.getDrawable()).getBitmap());

                // game args, check read, write exec
                EditText text_gameargs = view_gamedetail.findViewById(R.id.text_gameargs);
                ArrayList<String> gameargs = prepareOnsargs(gameinfo.getGameDir());
                String gamecmd = argsToCmd(gameargs);
                text_gameargs.setText(gamecmd);
                try {
                    if(m_gameargs!=null) text_gameargs.setEnabled(m_gameargs.getBoolean("alloweditargs"));
                    else text_gameargs.setEnabled(false);
                }
                catch (JSONException e){
                    text_gameargs.setEnabled(false);
                }

                CheckBox button_checkexec = view_gamedetail.findViewById(R.id.button_checkexec);
                CheckBox button_checkread = view_gamedetail.findViewById(R.id.button_checkread);
                CheckBox button_checkwrite = view_gamedetail.findViewById(R.id.button_checkwrite);
                button_checkread.setChecked((gamemod&4)!=0);
                button_checkwrite.setChecked((gamemod&2)!=0);
                button_checkexec.setChecked((gamemod&1)!=0);

                // game run
                Button button_run = view_gamedetail.findViewById(R.id.button_run);
                button_run.setOnClickListener(view -> {
                    String cmd = text_gameargs.getText().toString();
                    ArrayList<String> args;
                    if(!cmd.equals(gamecmd)) args = cmdToArgs(cmd);
                    else  args = gameargs;
                    startOnsyuri(gamemod, gameinfo, args);
                });

                // game detail popup
                PopupWindow window_config = new PopupWindow(MainActivity.this);
                window_config.setContentView(view_gamedetail);

                window_config.setFocusable(true); // close window when outside click
                window_config.setAnimationStyle(android.R.style.Animation_Toast);
                Configuration config = MainActivity.this.getResources().getConfiguration();
                if(config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    window_config.setWidth(LinearLayout.LayoutParams.WRAP_CONTENT);
                    window_config.setHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
                    window_config.showAtLocation(rootview,  Gravity.RIGHT | Gravity.BOTTOM, 0, 0);
                }
                else {
                    window_config.setWidth(LinearLayout.LayoutParams.MATCH_PARENT);
                    window_config.setHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
                    int gravity = Gravity.BOTTOM;
                    if(text_gameargs.isEnabled()) gravity = Gravity.CENTER;
                    window_config.showAtLocation(rootview,  gravity, 0, 0);
                }
            });
        }

        public void startOnsyuri(int mode, GameInfo gameinfo, ArrayList<String> args) {
            String safBase = gameinfo.m_isUri ? gameinfo.m_safBaseUri : null;
            if(mode==7)  {
                MainActivity.this.startOnsyuri(args, safBase, gameinfo.m_isUri);
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("Invalid Game Dir Warning");
            StringBuilder message = new StringBuilder();
            message.append("Gamepath: ").append(gameinfo.getRealPath()).append("\n");
            if((mode&4)==0) message.append("can not read !\n");
            if((mode&2)==0) message.append("can not write !\n");
            if((mode&1)==0) message.append("can not find 0.txt or default.ttf !\n");
            message.append("Do you still want to run ? ");
            builder.setMessage(message);
            builder.setCancelable(true);
            builder.setPositiveButton("Yes", (dialog, which) -> {
                dialog.dismiss();
                MainActivity.this.startOnsyuri(args, safBase, gameinfo.m_isUri);
            });
            builder.setNegativeButton("No", (dialog, which) -> dialog.dismiss());
            AlertDialog dialog = builder.create();
            dialog.show();
        }
    }

    // onsyuri info
    private String[] m_appdirs;

    private ArrayList<GameInfo> m_gamelist;
    private SharedPreferences m_sharedpref;
    private JSONObject m_gameargs;

    // onsyuri ui
    private TextView m_textgamedir;
    private GamelistAdapter m_listgameadaptor;
    private int m_gamelistGeneration = 0;
    private AlertDialog m_sourceDialog;

    public static String argsToCmd(ArrayList<String> args){
        StringBuilder cmd = new StringBuilder();
        for(int i=0;i<args.size();i++) {
            cmd.append(args.get(i)).append(" ");
        }
        return cmd.toString();
    }

    public static ArrayList<String> cmdToArgs(String cmd) {
        ArrayList<String> args = new ArrayList<>();
        for(String arg: cmd.split(" ")){
            if(arg==null || arg.length()==0) continue;
            args.add(arg);
        }
        return args;
    }

    public void test(){
        // storage/emulated/0/Android/data/com.yuri.onscripter/files/game/test
        File file = new File("/storage/emulated/0/Local/Game/ons_game/Noesis 羽化/0.txt");
        try {
            FileInputStream fin = new FileInputStream(file);
            int c = fin.read();
            fin.close();
        }
        catch (FileNotFoundException ignored){

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        setupWindowInsets();
        initValue();
        initUiGameConfig();
        initUiGameDir();
        updateGamelist();
    }

    /** keep the settings bar away from the status bar, cutout and navigation bar */
    private void setupWindowInsets() {
        View root = findViewById(R.id.root_main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultData){
        super.onActivityResult(requestCode, resultCode, resultData);
        if (requestCode == ACTIVITY_SAF ) {
            if(resultData==null) return;
            Uri uri = resultData.getData();
            if(uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            DocumentFile doc = DocumentFile.fromTreeUri(this, uri);
            String name = doc != null && doc.getName() != null ? doc.getName() : "saf";
            addSource(GameSources.newSource(GameSources.TYPE_SAF, name, uri.toString()));
        }
    }

    @Override
    public void onConfigurationChanged (@NonNull Configuration newConfig){
        super.onConfigurationChanged(newConfig);
        if (m_listgameadaptor != null) m_listgameadaptor.notifyDataSetChanged();
    }
    // onsyuri android info function
    private void initValue() {
        // init valus
        m_gamelist = new ArrayList<>();

        // load from sharedpref
        m_sharedpref = getSharedPreferences(SHAREDPREF_NAME, MODE_PRIVATE);
        GameSources.load(m_sharedpref); // migrate the old single dir settings

        // init dirs
        SafFile.init(this, m_sharedpref);
        SafFile.g_sharedprefname = SHAREDPREF_GAMEURI;
        m_appdirs = SafFile.getAppDirectories(this);
    }

    private ArrayList<String> prepareOnsargs(String gamedir){
        ArrayList<String> onsargs = new ArrayList<>();
        onsargs.add("--root");
        onsargs.add(gamedir);
        onsargs.add("--font");
        onsargs.add(gamedir + "/default.ttf");
        try {
            if(m_gameargs.getBoolean("strechfull")) onsargs.add("--fullscreen2");
            else onsargs.add("--fullscreen");
            if(m_gameargs.getBoolean("disablevideo")) onsargs.add("--no-video");
            String encoding = m_gameargs.getString("encoding");
            onsargs.add("--enc:" + encoding);
            if(m_gameargs.getBoolean("scopedsavedir")) {
                int idx;
                if(gamedir.charAt(gamedir.length()-1)!='/') idx =gamedir.lastIndexOf('/', gamedir.length()-2);
                else idx =gamedir.lastIndexOf('/');
                String gamename = idx >=0 ? gamedir.substring(idx+1) : gamedir;
                String savedir = getExternalFilesDir(null).toString();
                if(savedir.charAt(savedir.length()-1)!='/') savedir += '/';
                savedir += "save/" + gamename;

                File file = new File(savedir);
                if(file.exists() || file.mkdirs()) {
                    onsargs.add("--save-dir");
                    onsargs.add(savedir);
                }
            }
            if(m_gameargs.getBoolean("sharpness")) {
                String sharpness_value = m_gameargs.getString("sharpness_value");
                onsargs.add("--sharpness");
                onsargs.add(sharpness_value);
            }
        } catch (JSONException e) {
           Log.e("## onsyuri_android", e.toString());
        }
        return onsargs;
    }

    private void startOnsyuri(ArrayList<String> onsargs, String safBaseUri, boolean usesaf) {
        Intent intent = new Intent();
        intent.setClass(this, ONScripter.class);
        intent.putStringArrayListExtra(SHAREDPREF_GAMECONFIG, onsargs);

        if(usesaf && safBaseUri != null) {
            intent.putExtra(SHAREDPREF_GAMEURI, safBaseUri);
        }

        boolean ignorecutout = true;
        try{
            ignorecutout = m_gameargs.getBoolean("ignorecutout");
        }
        catch (JSONException e) {
            Log.e("## onsyuri_android", e.getMessage());
        }
        intent.putExtra("ignorecutout", ignorecutout);

        startActivity(intent);
    }

    // onsyuri android ui function

    private void initUiGameConfig(){
        updateGameConfig(true);
        LinearLayout layout_config = findViewById(R.id.layout_gameconfig);

        // checkbox, radiobutton, editbox
        for(int i=0; i<layout_config.getChildCount(); i++){
            View v = layout_config.getChildAt(i);
            if(v instanceof LinearLayout){
                for (int j=0; j<((LinearLayout)v).getChildCount();j++){
                    View v2 =((LinearLayout)v).getChildAt(j);
                    if(v2 instanceof CheckBox) v2.setOnClickListener(view -> updateGameConfig(false));
                    else if (v2 instanceof EditText) ((EditText)v2).addTextChangedListener(new TextWatcher() {
                        @Override
                        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                        @Override
                        public void onTextChanged(CharSequence s, int start, int before, int count) {}
                        @Override
                        public void afterTextChanged(Editable s) {updateGameConfig(false);}
                    });
                    else if (v2 instanceof RadioGroup) {
                        ((RadioGroup)v2).setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(RadioGroup group, int checkedId) {
                                updateGameConfig(false);
                            }
                        });
                    }
                }
            }
            else if(v instanceof CheckBox) { // config checkout
                v.setOnClickListener(view -> updateGameConfig(false));
            }
        }

        // config, check update, about button
        Button button_checkupdate = findViewById(R.id.button_checkupdate);
        button_checkupdate.setOnClickListener(view -> {
            Intent intent = new Intent();
            intent.setAction("android.intent.action.VIEW");
            intent.setData(Uri.parse("https://github.com/YuriSizuku/OnscripterYuri/releases"));
            startActivity(intent);
        });
        Button button_about = findViewById(R.id.button_about);
        button_about.setOnClickListener(view -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle(getResources().getString(R.string.app_name));
            builder.setMessage(getResources().getString(R.string.app_about));
            builder.setCancelable(true);
            builder.setPositiveButton("Ok", (dialog, which) -> dialog.dismiss());
            AlertDialog dialog = builder.create();
            dialog.show();
        });
        // game dir sources and gamepad key mapping
        Button button_sources = findViewById(R.id.button_sources);
        button_sources.setOnClickListener(view -> showSourceManager());
        Button button_gamepad = findViewById(R.id.button_gamepad);
        button_gamepad.setOnClickListener(view -> showGamepadDialog());

        View scroll_config = findViewById(R.id.scroll_gameconfig);
        ImageButton button_config = findViewById(R.id.button_config);
        button_config.setOnClickListener(view->{
            int visibility = scroll_config.getVisibility();
            if(visibility==View.VISIBLE)  {
                scroll_config.setVisibility(View.GONE);
            }
            else {
                scroll_config.setAlpha(0);
                scroll_config.setVisibility(View.VISIBLE);
                scroll_config.animate().alpha(1f).setDuration(200).setListener(null);
            }
        });
    }
    private void initUiGameDir(){
        ImageButton button_explore = findViewById(R.id.button_explore);
        button_explore.setOnClickListener(view -> showSourceManager());

        // game dir source summary, click to manage the saved sources
        m_textgamedir = findViewById(R.id.text_gamedir);
        m_textgamedir.setOnClickListener(view -> showSourceManager());
        updateSourceSummary();

        // game list
        ListView listgame = findViewById(R.id.list_game);
        m_listgameadaptor = new GamelistAdapter();
        listgame.setAdapter(m_listgameadaptor);
    }

    private void updateGameConfig(boolean init) {

        CheckBox button_strechfull = findViewById(R.id.button_stretchfull);
        CheckBox button_ignorecutout = findViewById(R.id.button_ignorecutout);
        CheckBox button_gles2sharpness =findViewById(R.id.button_gles2sharpness);
        TextView text_gles2sharpness = findViewById(R.id.text_gles2sharpness);
        CheckBox button_disablevideo = findViewById(R.id.button_disablevideo);
        RadioButton button_sjis = findViewById(R.id.button_sjis);
        RadioButton button_gbk = findViewById(R.id.button_gbk);
        RadioButton button_utf8 = findViewById(R.id.button_utf8);
        CheckBox button_scopedsavedir = findViewById(R.id.button_scopedsavedir);
        CheckBox button_alloweditargs = findViewById(R.id.button_alloweditargs);
        try {
            if(init) { // config -> view
                String jsonstr = m_sharedpref.getString(SHAREDPREF_GAMECONFIG, null);
                if(jsonstr==null) m_gameargs = new JSONObject();
                else m_gameargs = new JSONObject(jsonstr);
                button_strechfull.setChecked(m_gameargs.getBoolean("strechfull"));
                button_ignorecutout.setChecked(m_gameargs.getBoolean("ignorecutout"));
                button_gles2sharpness.setChecked(m_gameargs.getBoolean("sharpness"));
                button_disablevideo.setChecked(m_gameargs.getBoolean("disablevideo"));
                String encoding = m_gameargs.getString("encoding");
                if(encoding.equals("gbk")) button_gbk.setChecked(true);
                else if(encoding.equals("sjis")) button_sjis.setChecked(true);
                else if(encoding.equals("utf8")) button_utf8.setChecked(true);
                else button_gbk.setChecked(true);
                button_scopedsavedir.setChecked(m_gameargs.getBoolean("scopedsavedir"));
                button_alloweditargs.setChecked(m_gameargs.getBoolean("alloweditargs"));
                text_gles2sharpness.setText(m_gameargs.getString("sharpness_value"));
            }
            else { // view -> config
                if(m_gameargs == null) m_gameargs = new JSONObject();
                m_gameargs.put("strechfull", button_strechfull.isChecked());
                m_gameargs.put("ignorecutout", button_ignorecutout.isChecked());
                m_gameargs.put("sharpness", button_gles2sharpness.isChecked());
                m_gameargs.put("disablevideo", button_disablevideo.isChecked());
                m_gameargs.put("encoding", button_utf8.isPressed() ? "utf8" : button_sjis.isPressed() ?  "sjis" : "gbk");
                m_gameargs.put("scopedsavedir", button_scopedsavedir.isChecked());
                m_gameargs.put("alloweditargs", button_alloweditargs.isChecked());
                String sharpness_value = text_gles2sharpness.getText().toString();
                try {
                    Double.valueOf(sharpness_value);

                } catch (NumberFormatException e){
                    sharpness_value = "10.0";
                }
                m_gameargs.put("sharpness_value",  sharpness_value);
                m_sharedpref.edit().putString(SHAREDPREF_GAMECONFIG, m_gameargs.toString()).apply();
            }
        }
        catch (JSONException e){
            Log.e("## onsyuri_android", e.toString());
        }
    }

    private void updateGamelist() {
        m_gamelist.clear();
        int generation = ++m_gamelistGeneration;
        List<GameSources.Source> sources = GameSources.load(m_sharedpref);
        for (GameSources.Source source : sources) {
            if (!source.enabled) continue;
            if (source.isApp()) scanAppSource();
            else if (source.isSaf()) scanSafSource(source);
            else if (source.isSmb()) loadSmbGamesAsync(source, generation);
            else scanLocalSource(source);
        }
        if (m_listgameadaptor != null) m_listgameadaptor.notifyDataSetChanged();
        updateSourceSummary();
    }

    private void updateSourceSummary() {
        if (m_textgamedir == null) return;
        List<GameSources.Source> sources = GameSources.load(m_sharedpref);
        StringBuilder sb = new StringBuilder();
        for (GameSources.Source source : sources) {
            if (!source.enabled) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(source.displayName());
        }
        if (sb.length() == 0) sb.append("no game dir enabled, click to add");
        m_textgamedir.setText(sb.toString());
        m_textgamedir.setTextColor(Color.parseColor(sb.length() == "no game dir enabled, click to add".length()
                ? COLOR_NOTDIR : COLOR_INRDIR));
    }

    // ------------------------------------------------------- game list scan

    private void scanAppSource() {
        for (String appdir : m_appdirs) {
            File dir = new File(appdir);
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file == null || !file.isDirectory()) continue;
                String name = file.getName();
                if (name.equals("save")) continue;
                if (name.equals("smb")) {
                    scanDownloadedSmbGames(file);
                    continue;
                }
                addLocalGame(file);
            }
        }
    }

    private void scanDownloadedSmbGames(File smbDir) {
        File[] files = smbDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isDirectory()) continue;
            addLocalGame(file);
        }
    }

    private void scanLocalSource(GameSources.Source source) {
        File dir = new File(source.path);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isDirectory()) continue;
            addLocalGame(file);
        }
    }

    private void addLocalGame(File file) {
        GameInfo gameinfo = new GameInfo(file.getAbsolutePath(), false);
        if (gameinfo.m_name != null) m_gamelist.add(gameinfo);
    }

    private void scanSafSource(GameSources.Source source) {
        try {
            DocumentFile base = DocumentFile.fromTreeUri(this, Uri.parse(source.path));
            if (base == null) return;
            for (DocumentFile docfile : base.listFiles()) {
                if (docfile == null || !docfile.isDirectory()) continue;
                GameInfo gameinfo = new GameInfo(docfile.getUri().toString(), true, source.path);
                if (gameinfo.m_name == null) gameinfo.m_name = docfile.getName();
                if (gameinfo.m_name != null) m_gamelist.add(gameinfo);
            }
        } catch (Exception e) {
            Log.w("## onsyuri_android", "saf source failed: " + e.getMessage());
        }
    }

    /** list the remote directories of an smb source in the background, failures are skipped */
    private void loadSmbGamesAsync(final GameSources.Source source, final int generation) {
        new Thread(() -> {
            List<GameInfo> games = new ArrayList<>();
            SmbClient client = new SmbClient();
            String error = null;
            try {
                connectSource(client, source);
                String sub = GameSources.smbSubPath(source);
                List<SmbClient.SmbEntry> entries = client.list(sub);
                for (SmbClient.SmbEntry entry : entries) {
                    if (!entry.isDirectory) continue;
                    String remote = SmbClient.join(sub, entry.name);
                    games.add(new GameInfo(source, remote, entry.name));
                }
            } catch (Exception e) {
                error = e.getMessage();
                Log.w("## onsyuri_android", "smb source failed, skipped: "
                        + source.displayPath() + " : " + error);
            } finally {
                client.close();
            }
            final String failure = error;
            runOnUiThread(() -> {
                if (generation != m_gamelistGeneration) return;
                m_gamelist.addAll(games);
                if (m_listgameadaptor != null) m_listgameadaptor.notifyDataSetChanged();
                if (failure != null) {
                    Toast.makeText(MainActivity.this,
                            "SMB " + source.displayName() + " failed: " + failure,
                            Toast.LENGTH_LONG).show();
                } else if (games.isEmpty()) {
                    Toast.makeText(MainActivity.this,
                            "SMB " + source.displayName() + ": connected, but no game folder found",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }, "smb-list").start();
    }

    private static void connectSource(SmbClient client, GameSources.Source source) throws IOException {
        client.connect(GameSources.smbHost(source),
                source.port <= 0 ? GameSources.DEFAULT_SMB_PORT : source.port,
                GameSources.smbShare(source), source.user, source.password);
    }

    // ------------------------------------------------------ source manager ui

    private void showSourceManager() {
        dismissSourceManager();
        final List<GameSources.Source> sources = GameSources.load(m_sharedpref);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);
        int rowPad = (int) (8 * density);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < sources.size(); i++) {
            final int index = i;
            final GameSources.Source source = sources.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, rowPad, 0, rowPad);

            CheckBox check = new CheckBox(this);
            check.setChecked(source.enabled);
            check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                List<GameSources.Source> list = GameSources.load(m_sharedpref);
                if (index >= 0 && index < list.size()) {
                    list.get(index).enabled = isChecked;
                    GameSources.save(m_sharedpref, list);
                }
                updateSourceSummary();
                updateGamelist();
            });

            TextView info = new TextView(this);
            info.setText(source.displayName() + "\n" + source.displayPath());
            info.setTextSize(12);
            info.setPadding(rowPad, 0, rowPad, 0);
            info.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            info.setOnClickListener(v -> showSourceEditDialog(index));
            info.setOnLongClickListener(v -> {
                confirmDeleteSource(index);
                return true;
            });

            row.addView(check);
            row.addView(info);
            content.addView(row);
        }

        TextView hint = new TextView(this);
        hint.setText("Tap a source to edit, long press to delete. Multiple sources can be enabled at the same time.");
        hint.setTextSize(11);
        hint.setPadding(0, rowPad, 0, rowPad);
        content.addView(hint);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        Button addSaf = new Button(this);
        addSaf.setText("add local dir");
        addSaf.setTextSize(11);
        addSaf.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addSaf.setOnClickListener(v -> {
            dismissSourceManager();
            SafFile.requestDocUri(this, ACTIVITY_SAF);
        });
        Button addPath = new Button(this);
        addPath.setText("add local path");
        addPath.setTextSize(11);
        addPath.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addPath.setOnClickListener(v -> showLocalPathDialog(null, -1));
        addRow.addView(addSaf);
        addRow.addView(addPath);
        content.addView(addRow);

        Button addSmb = new Button(this);
        addSmb.setText("add SMB source (SMB2 / SMB3)");
        addSmb.setTextSize(11);
        addSmb.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addSmb.setOnClickListener(v -> showSmbSourceDialog(null, -1));
        content.addView(addSmb);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("game dir sources");
        builder.setView(scroll);
        builder.setPositiveButton("close", (dialog, which) -> dialog.dismiss());
        final AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            if (m_sourceDialog == dialog) m_sourceDialog = null;
        });
        m_sourceDialog = dialog;
        dialog.show();
    }

    private void dismissSourceManager() {
        if (m_sourceDialog != null) {
            AlertDialog dialog = m_sourceDialog;
            m_sourceDialog = null;
            dialog.dismiss();
        }
    }

    private void addSource(GameSources.Source source) {
        List<GameSources.Source> list = GameSources.load(m_sharedpref);
        list.add(source);
        GameSources.save(m_sharedpref, list);
        updateSourceSummary();
        updateGamelist();
        dismissSourceManager();
        showSourceManager();
    }

    private void showSourceEditDialog(final int index) {
        List<GameSources.Source> list = GameSources.load(m_sharedpref);
        if (index < 0 || index >= list.size()) return;
        GameSources.Source source = list.get(index);
        if (source.isSmb()) {
            showSmbSourceDialog(source, index);
        } else if (GameSources.TYPE_LOCAL.equals(source.type)) {
            showLocalPathDialog(source, index);
        } else {
            Toast.makeText(this, source.displayName() + " is not editable, long press to delete",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteSource(final int index) {
        final List<GameSources.Source> list = GameSources.load(m_sharedpref);
        if (index < 0 || index >= list.size()) return;
        final GameSources.Source source = list.get(index);
        new AlertDialog.Builder(this)
                .setTitle("delete source")
                .setMessage(source.displayName() + "\n" + source.displayPath())
                .setPositiveButton("delete", (dialog, which) -> {
                    List<GameSources.Source> current = GameSources.load(m_sharedpref);
                    if (index >= 0 && index < current.size()) current.remove(index);
                    GameSources.save(m_sharedpref, current);
                    updateSourceSummary();
                    updateGamelist();
                    dismissSourceManager();
                    showSourceManager();
                })
                .setNegativeButton("cancel", null)
                .show();
    }

    private void showLocalPathDialog(final GameSources.Source existing, final int index) {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("/storage/emulated/0/...");
        input.setSingleLine(true);
        if (existing != null) input.setText(existing.path);
        input.setPadding(pad, pad, pad, pad);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("local game dir")
                .setView(input)
                .setPositiveButton("save", null)
                .setNegativeButton("cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String path = input.getText().toString().trim();
                    File file = new File(path);
                    if (!file.isDirectory()) {
                        Toast.makeText(this, "not a readable directory", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<GameSources.Source> list = GameSources.load(m_sharedpref);
                    if (existing != null && index >= 0 && index < list.size()) {
                        GameSources.Source source = list.get(index);
                        source.type = GameSources.TYPE_LOCAL;
                        source.path = path;
                        if (source.name == null || source.name.length() == 0) source.name = file.getName();
                    } else {
                        list.add(GameSources.newSource(GameSources.TYPE_LOCAL, file.getName(), path));
                    }
                    GameSources.save(m_sharedpref, list);
                    dialog.dismiss();
                    updateSourceSummary();
                    updateGamelist();
                    dismissSourceManager();
                    showSourceManager();
                }));
        dialog.show();
    }

    private void showSmbSourceDialog(final GameSources.Source existing, final int index) {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);
        int fieldPad = (int) (4 * density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        EditText address = new EditText(this);
        address.setHint("host / IP, or host/share/sub");
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT);
        if (existing != null) address.setText(existing.path);
        box.addView(address);
        address.setPadding(0, fieldPad, 0, fieldPad);

        EditText port = new EditText(this);
        port.setHint("port (445)");
        port.setSingleLine(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(existing != null ? existing.port : GameSources.DEFAULT_SMB_PORT));
        box.addView(port);
        port.setPadding(0, fieldPad, 0, fieldPad);

        EditText user = new EditText(this);
        user.setHint("user (DOMAIN\\user)");
        user.setSingleLine(true);
        user.setInputType(InputType.TYPE_CLASS_TEXT);
        if (existing != null) user.setText(existing.user);
        box.addView(user);
        user.setPadding(0, fieldPad, 0, fieldPad);

        EditText password = new EditText(this);
        password.setHint("password");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (existing != null) password.setText(existing.password);
        box.addView(password);
        password.setPadding(0, fieldPad, 0, fieldPad);

        EditText name = new EditText(this);
        name.setHint("display name (optional)");
        name.setSingleLine(true);
        if (existing != null) name.setText(existing.name);
        box.addView(name);
        name.setPadding(0, fieldPad, 0, fieldPad);

        Button browse = new Button(this);
        browse.setText("connect and browse folders");
        browse.setTextSize(11);
        box.addView(browse);

        TextView note = new TextView(this);
        note.setText("Address example: 192.168.1.10/share/subfolder\n"
                + "Email accounts can be used as the user name.\n"
                + "The password is saved in the app preferences; an unreachable source is skipped automatically.");
        note.setTextSize(10);
        box.addView(note);

        MaxHeightScrollView scroll = new MaxHeightScrollView(this, null);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("SMB source")
                .setView(scroll)
                .setPositiveButton("save", null)
                .setNegativeButton("cancel", null)
                .setNeutralButton("test", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String addr = GameSources.cleanAddress(address.getText().toString());
                GameSources.Source probe = GameSources.newSource(GameSources.TYPE_SMB, "", addr);
                if (GameSources.smbHost(probe).length() == 0
                        || GameSources.smbShare(probe).length() == 0) {
                    Toast.makeText(this, "use the format host/share[/sub]", Toast.LENGTH_SHORT).show();
                    return;
                }
                int p;
                try {
                    p = Integer.parseInt(port.getText().toString().trim());
                } catch (NumberFormatException e) {
                    p = GameSources.DEFAULT_SMB_PORT;
                }
                final Button testButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                testButton.setEnabled(false);
                testButton.setText("testing...");
                final String host = GameSources.smbHost(probe);
                final String share = GameSources.smbShare(probe);
                final String sub = GameSources.smbSubPath(probe);
                final int fport = p;
                final String fuser = user.getText().toString().trim();
                final String fpass = password.getText().toString();
                new Thread(() -> {
                    String result;
                    SmbClient client = new SmbClient();
                    try {
                        client.connect(host, fport, share, fuser, fpass);
                        List<SmbClient.SmbEntry> entries = client.list(sub);
                        int dirs = 0;
                        for (SmbClient.SmbEntry entry : entries) {
                            if (entry.isDirectory) dirs++;
                        }
                        result = "OK: " + client.getDialectName() + "\n"
                                + entries.size() + " entries, " + dirs + " folders";
                        if (dirs == 0) result += "\nNo game folder found at this path.";
                    } catch (Exception e) {
                        result = "FAILED: " + e.getMessage();
                    } finally {
                        client.close();
                    }
                    final String message = result;
                    runOnUiThread(() -> {
                        if (!dialog.isShowing()) return;
                        testButton.setEnabled(true);
                        testButton.setText("test");
                        new AlertDialog.Builder(this)
                                .setTitle("SMB test")
                                .setMessage(message)
                                .setPositiveButton("ok", null)
                                .show();
                    });
                }, "smb-test").start();
            });
            browse.setOnClickListener(v -> {
                String addr = GameSources.cleanAddress(address.getText().toString());
                GameSources.Source probe = GameSources.newSource(GameSources.TYPE_SMB, "", addr);
                final String host = GameSources.smbHost(probe);
                if (host.length() == 0) {
                    Toast.makeText(this, "enter the server IP / host first", Toast.LENGTH_SHORT).show();
                    return;
                }
                int p;
                try {
                    p = Integer.parseInt(port.getText().toString().trim());
                } catch (NumberFormatException e) {
                    p = GameSources.DEFAULT_SMB_PORT;
                }
                final int fport = p;
                final String fuser = user.getText().toString().trim();
                final String fpass = password.getText().toString();
                final String share = GameSources.smbShare(probe);
                final String sub = GameSources.smbSubPath(probe);
                if (share.length() == 0) {
                    EditText input = new EditText(this);
                    input.setHint("share name (e.g. server)");
                    new AlertDialog.Builder(this)
                            .setTitle("SMB share")
                            .setMessage("Enter the share name on " + host)
                            .setView(input)
                            .setPositiveButton("next", (dd, w) -> {
                                String s = GameSources.cleanAddress(input.getText().toString());
                                if (s.length() == 0) {
                                    Toast.makeText(this, "share name is empty", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                showSmbFolderPicker(host, fport, s, "", fuser, fpass, address);
                            })
                            .setNegativeButton("cancel", null)
                            .show();
                } else {
                    showSmbFolderPicker(host, fport, share, sub, fuser, fpass, address);
                }
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String addr = GameSources.cleanAddress(address.getText().toString());
                    GameSources.Source probe = GameSources.newSource(GameSources.TYPE_SMB, "", addr);
                    if (GameSources.smbHost(probe).length() == 0
                            || GameSources.smbShare(probe).length() == 0) {
                        Toast.makeText(this, "use the format host/share[/sub]", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int p;
                    try {
                        p = Integer.parseInt(port.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        p = GameSources.DEFAULT_SMB_PORT;
                    }
                    String displayName = name.getText().toString().trim();
                    if (displayName.length() == 0) {
                        displayName = GameSources.smbHost(probe) + "/" + GameSources.smbShare(probe);
                    }

                    List<GameSources.Source> list = GameSources.load(m_sharedpref);
                    GameSources.Source target;
                    if (existing != null && index >= 0 && index < list.size()) {
                        target = list.get(index);
                    } else {
                        target = new GameSources.Source();
                        list.add(target);
                    }
                    target.type = GameSources.TYPE_SMB;
                    target.path = addr;
                    target.port = p;
                    target.user = user.getText().toString().trim();
                    target.password = password.getText().toString();
                    target.name = displayName;
                    target.enabled = true;
                    GameSources.save(m_sharedpref, list);

                    dialog.dismiss();
                    updateSourceSummary();
                    updateGamelist();
                    dismissSourceManager();
                    showSourceManager();
                });
            });
        dialog.show();
    }

    // ------------------------------------------------------ smb folder picker

    /** connect with the entered ip/user/password, then let the user pick a folder */
    private void showSmbFolderPicker(final String host, final int port, final String share,
                                     final String initialPath, final String user, final String pass,
                                     final EditText addressField) {
        final SmbClient client = new SmbClient();
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("SMB browse");
        progress.setMessage("connecting to " + host + "/" + share);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            String error = null;
            List<SmbClient.SmbEntry> entries = null;
            try {
                client.connect(host, port, share, user, pass);
                entries = client.list(initialPath);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String failure = error;
            final List<SmbClient.SmbEntry> firstEntries = entries;
            runOnUiThread(() -> {
                progress.dismiss();
                if (failure != null) {
                    client.close();
                    new AlertDialog.Builder(this)
                            .setTitle("SMB browse failed")
                            .setMessage(failure)
                            .setPositiveButton("ok", null)
                            .show();
                    return;
                }
                showSmbPickerDialog(client, share, initialPath, firstEntries, addressField);
            });
        }, "smb-browse").start();
    }

    private void showSmbPickerDialog(final SmbClient client, final String share,
                                     final String startPath,
                                     final List<SmbClient.SmbEntry> initialEntries,
                                     final EditText addressField) {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        final ListView list = new ListView(this);
        final List<String> items = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, items);
        list.setAdapter(adapter);
        int listHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, listHeight));
        content.addView(list);

        final String[] currentPath = {startPath};
        final boolean[] loading = {false};

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("smb://" + share)
                .setView(content)
                .setPositiveButton("select this folder", null)
                .setNeutralButton("up", null)
                .setNegativeButton("cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (loading[0]) return;
                String selected = currentPath[0];
                String full = client.getHost() + "/" + client.getShare();
                if (selected.length() > 0) full += "/" + selected;
                addressField.setText(full);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (loading[0]) return;
                String parent = SmbClient.parent(currentPath[0]);
                if (parent == null) parent = "";
                loadSmbFolder(client, share, parent, adapter, items, currentPath, dialog, list, loading);
            });
        });
        dialog.setOnDismissListener(d -> client.close());
        list.setOnItemClickListener((parentView, view, position, id) -> {
            if (loading[0] || position < 0 || position >= items.size()) return;
            String name = items.get(position);
            String next;
            if (name.equals("..")) {
                next = SmbClient.parent(currentPath[0]);
                if (next == null) next = "";
            } else {
                next = SmbClient.join(currentPath[0], name);
            }
            loadSmbFolder(client, share, next, adapter, items, currentPath, dialog, list, loading);
        });

        dialog.show();
        populateSmbFolderItems(items, adapter, dialog, share, startPath, initialEntries);
    }

    private void loadSmbFolder(final SmbClient client, final String share, final String path,
                               final ArrayAdapter<String> adapter, final List<String> items,
                               final String[] currentPath, final AlertDialog dialog,
                               final ListView list, final boolean[] loading) {
        if (loading[0]) return;
        loading[0] = true;
        list.setEnabled(false);
        dialog.setTitle("smb://" + share + (path.length() > 0 ? "/" + path : "") + "  (loading...)");
        new Thread(() -> {
            List<SmbClient.SmbEntry> entries = null;
            String error = null;
            try {
                entries = client.list(path);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String failure = error;
            final List<SmbClient.SmbEntry> result = entries;
            runOnUiThread(() -> {
                loading[0] = false;
                if (!dialog.isShowing()) return;
                list.setEnabled(true);
                if (failure != null) {
                    dialog.setTitle("smb://" + share + (path.length() > 0 ? "/" + path : ""));
                    Toast.makeText(MainActivity.this, "SMB list failed: " + failure,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                currentPath[0] = path;
                populateSmbFolderItems(items, adapter, dialog, share, path, result);
            });
        }, "smb-list-dir").start();
    }

    private void populateSmbFolderItems(List<String> items, ArrayAdapter<String> adapter,
                                        AlertDialog dialog, String share, String path,
                                        List<SmbClient.SmbEntry> entries) {
        items.clear();
        if (path.length() > 0) items.add("..");
        List<SmbClient.SmbEntry> dirs = new ArrayList<>();
        if (entries != null) {
            for (SmbClient.SmbEntry entry : entries) {
                if (entry.isDirectory) dirs.add(entry);
            }
        }
        Collections.sort(dirs, (a, b) -> a.name.compareToIgnoreCase(b.name));
        for (SmbClient.SmbEntry dir : dirs) items.add(dir.name);
        adapter.notifyDataSetChanged();
        String title = "smb://" + share;
        if (path.length() > 0) title += "/" + path;
        dialog.setTitle(title);
    }

    // --------------------------------------------------------- smb download

    private void showRemoteGameDetail(final GameInfo gameinfo) {
        new AlertDialog.Builder(this)
                .setTitle(gameinfo.m_name)
                .setMessage("SMB: " + gameinfo.getRealPath()
                        + "\n\nRun directly over the network: each file is fetched when the game "
                        + "needs it, so there is no full download. Save files stay on the device.")
                .setPositiveButton("run (virtual path)", (dialog, which) -> runRemoteVirtual(gameinfo))
                .setNeutralButton("download and run", (dialog, which) -> downloadAndRunRemote(gameinfo))
                .setNegativeButton("cancel", null)
                .show();
    }

    // ------------------------------------------------------ smb virtual run

    private void runRemoteVirtual(final GameInfo gameinfo) {
        if (gameinfo == null || gameinfo.m_smbSource == null) {
            Toast.makeText(this, "the smb source is missing", Toast.LENGTH_SHORT).show();
            return;
        }
        final GameSources.Source source = gameinfo.m_smbSource;
        ArrayList<String> args = prepareOnsargs(gameinfo.getVirtualRoot());

        // keep the save files (and logs) in the app storage, not in the smb cache
        boolean hasSaveDir = false;
        for (String arg : args) {
            if ("--save-dir".equals(arg)) {
                hasSaveDir = true;
                break;
            }
        }
        if (!hasSaveDir) {
            File savedir = new File(new File(getExternalFilesDir(null), "save"),
                    safeFileName(gameinfo.m_name));
            if (savedir.exists() || savedir.mkdirs()) {
                args.add("--save-dir");
                args.add(savedir.getAbsolutePath());
            }
        }

        Intent intent = new Intent();
        intent.setClass(this, ONScripter.class);
        intent.putStringArrayListExtra(SHAREDPREF_GAMECONFIG, args);
        intent.putExtra(SHAREDPREF_SMB_TOKEN, gameinfo.getVirtualToken());
        intent.putExtra(SHAREDPREF_SMB_HOST, GameSources.smbHost(source));
        intent.putExtra(SHAREDPREF_SMB_PORT,
                source.port <= 0 ? GameSources.DEFAULT_SMB_PORT : source.port);
        intent.putExtra(SHAREDPREF_SMB_SHARE, GameSources.smbShare(source));
        intent.putExtra(SHAREDPREF_SMB_BASE, gameinfo.m_path);
        intent.putExtra(SHAREDPREF_SMB_USER, source.user);
        intent.putExtra(SHAREDPREF_SMB_PASSWORD, source.password);

        boolean ignorecutout = true;
        try {
            ignorecutout = m_gameargs.getBoolean("ignorecutout");
        } catch (JSONException e) {
            Log.e("## onsyuri_android", e.getMessage());
        }
        intent.putExtra("ignorecutout", ignorecutout);

        startActivity(intent);
    }

    private void downloadAndRunRemote(final GameInfo gameinfo) {
        if (gameinfo == null || gameinfo.m_smbSource == null) {
            Toast.makeText(this, "the smb source is missing", Toast.LENGTH_SHORT).show();
            return;
        }
        final GameSources.Source source = gameinfo.m_smbSource;
        final String gameName = gameinfo.m_name == null ? "game" : gameinfo.m_name;
        final File target = new File(smbDownloadRoot(), safeFileName(gameName));

        final ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("SMB download");
        progress.setMessage(gameName);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            String error = null;
            SmbClient client = new SmbClient();
            try {
                connectSource(client, source);
                long total = remoteTreeSize(client, gameinfo.m_path);
                if (total > 0) runOnUiThread(() -> {
                    if (progress.isShowing()) {
                        progress.setIndeterminate(false);
                        progress.setMax(100);
                        progress.setProgress(0);
                    }
                });
                downloadRemoteTree(client, gameinfo.m_path, target, total, 0, progress, gameName);
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                client.close();
            }
            final String failed = error;
            runOnUiThread(() -> {
                progress.dismiss();
                if (failed != null) {
                    // smb is unreachable, just skip it, do not crash the app
                    Toast.makeText(MainActivity.this, "SMB failed, skipped: " + failed,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                updateGamelist();
                ArrayList<String> args = prepareOnsargs(target.getAbsolutePath());
                startOnsyuri(args, null, false);
            });
        }, "smb-download").start();
    }

    private File smbDownloadRoot() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        return new File(base, "smb");
    }

    private static String safeFileName(String name) {
        if (name == null || name.length() == 0) return "game";
        StringBuilder sb = new StringBuilder(name.length());
        String invalid = "\\/:*?\"<>|";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (invalid.indexOf(c) >= 0 || c < 0x20) sb.append('_');
            else sb.append(c);
        }
        String result = sb.toString().trim();
        return result.length() == 0 ? "game" : result;
    }

    private long remoteTreeSize(SmbClient client, String remotePath) throws IOException {
        long total = 0;
        List<SmbClient.SmbEntry> entries = client.list(remotePath);
        for (SmbClient.SmbEntry entry : entries) {
            String remote = SmbClient.join(remotePath, entry.name);
            if (entry.isDirectory) total += remoteTreeSize(client, remote);
            else if (entry.size > 0) total += entry.size;
        }
        return total;
    }

    private long downloadRemoteTree(SmbClient client, String remotePath, File localDir,
                                    long total, long done, ProgressDialog progress, String label)
            throws IOException {
        if (!localDir.exists() && !localDir.mkdirs()) {
            throw new IOException("can not create " + localDir.getAbsolutePath());
        }
        List<SmbClient.SmbEntry> entries = client.list(remotePath);
        long current = done;
        long[] lastUpdate = {0};
        for (SmbClient.SmbEntry entry : entries) {
            String remote = SmbClient.join(remotePath, entry.name);
            File local = new File(localDir, entry.name);
            if (entry.isDirectory) {
                current = downloadRemoteTree(client, remote, local, total, current, progress, label);
            } else {
                final long base = current;
                final String message = label + " / " + entry.name;
                FileOutputStream out = new FileOutputStream(local);
                try {
                    current += client.download(remote, out, (d, t) ->
                            reportDownloadProgress(progress, base + d, total, message, lastUpdate));
                } finally {
                    out.close();
                }
                reportDownloadProgress(progress, current, total, message, lastUpdate);
            }
        }
        return current;
    }

    private void reportDownloadProgress(ProgressDialog progress, long done, long total,
                                        String message, long[] lastUpdate) {
        long now = System.currentTimeMillis();
        if (now - lastUpdate[0] < 120) return;
        lastUpdate[0] = now;
        runOnUiThread(() -> {
            if (!progress.isShowing()) return;
            if (total > 0) {
                progress.setIndeterminate(false);
                progress.setMax(100);
                progress.setProgress((int) Math.min(100, done * 100 / total));
            }
            progress.setMessage(message);
        });
    }

    // --------------------------------------------------------- gamepad ui

    private void showGamepadDialog() {
        final GamepadMapping map = GamepadMapping.load(m_sharedpref);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        CheckBox leftStick = new CheckBox(this);
        leftStick.setText("left stick = DPAD");
        leftStick.setChecked(map.isLeftStickDpad());
        leftStick.setOnCheckedChangeListener((buttonView, isChecked) -> {
            map.setLeftStickDpad(isChecked);
            map.save(m_sharedpref);
        });
        root.addView(leftStick);

        TextView hint = new TextView(this);
        hint.setText("Press a gamepad button, then choose the keyboard key it should send.");
        hint.setTextSize(11);
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);

        final Button[] actionButtons = new Button[map.getButtonCount()];
        for (int i = 0; i < map.getButtonCount(); i++) {
            final int buttonIndex = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (4 * density), 0, (int) (4 * density));

            TextView name = new TextView(this);
            name.setText(map.getButtonName(i));
            name.setTextSize(13);
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final Button action = new Button(this);
            action.setText(map.getButtonActionName(i));
            action.setTextSize(10);
            action.setMinimumWidth(0);
            action.setMinWidth(0);
            action.setPadding(pad, 0, pad, 0);
            action.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(map.getButtonName(buttonIndex))
                    .setSingleChoiceItems(GamepadMapping.ACTION_NAMES,
                            map.getButtonActionIndex(buttonIndex), (dialog, which) -> {
                                map.setButtonActionIndex(buttonIndex, which);
                                map.save(m_sharedpref);
                                action.setText(map.getButtonActionName(buttonIndex));
                                dialog.dismiss();
                            })
                    .show());
            actionButtons[i] = action;

            row.addView(name);
            row.addView(action);
            rows.addView(row);
        }
        scroll.addView(rows);
        int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height));
        root.addView(scroll);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("gamepad key mapping")
                .setView(root)
                .setPositiveButton("done", null)
                .setNeutralButton("reset defaults", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> {
                    map.resetDefaults();
                    map.save(m_sharedpref);
                    leftStick.setChecked(true);
                    for (int i = 0; i < actionButtons.length; i++) {
                        actionButtons[i].setText(map.getButtonActionName(i));
                    }
                }));
        dialog.show();
    }

}