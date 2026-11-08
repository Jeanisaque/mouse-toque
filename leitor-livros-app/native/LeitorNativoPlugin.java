package com.touchcell.leitor;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Funcoes nativas do Leitor de Livros: acesso a arquivos, atualizacao do app e wake lock. */
@CapacitorPlugin(name = "LeitorNativo")
public class LeitorNativoPlugin extends Plugin {

    private PowerManager.WakeLock wakeLock;

    // ---------- Acesso aos arquivos ----------
    private boolean temAcesso() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void acessoArquivos(PluginCall call) {
        JSObject r = new JSObject();
        r.put("concedido", temAcesso());
        call.resolve(r);
    }

    @PluginMethod
    public void pedirAcessoArquivos(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } else {
            ActivityCompat.requestPermissions(getActivity(),
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 4711);
        }
        call.resolve();
    }

    private static boolean ehLivro(String nome) {
        String n = nome.toLowerCase(Locale.ROOT);
        return n.endsWith(".epub") || n.endsWith(".pdf") || n.endsWith(".txt") || n.endsWith(".html") || n.endsWith(".htm");
    }

    private void varrer(File dir, int nivel, List<File> out) {
        if (nivel > 4 || out.size() >= 500) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) varrer(f, nivel + 1, out);
            else if (ehLivro(f.getName())) out.add(f);
        }
    }

    @PluginMethod
    public void procurarLivros(PluginCall call) {
        if (!temAcesso()) {
            call.reject("sem permissao de arquivos");
            return;
        }
        File raiz = Environment.getExternalStorageDirectory();
        List<File> achados = new ArrayList<>();
        varrer(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), 0, achados);
        varrer(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), 0, achados);
        varrer(new File(raiz, "Books"), 0, achados);
        varrer(new File(raiz, "Livros"), 0, achados);
        JSArray arr = new JSArray();
        for (File f : achados) {
            JSObject o = new JSObject();
            o.put("nome", f.getName());
            o.put("caminho", f.getAbsolutePath());
            o.put("tamanho", f.length());
            arr.put(o);
        }
        JSObject r = new JSObject();
        r.put("livros", arr);
        call.resolve(r);
    }

    @PluginMethod
    public void lerBloco(PluginCall call) {
        String caminho = call.getString("caminho");
        Long off = call.getLong("offset", 0L);
        Integer tam = call.getInt("tamanho", 3 * 1024 * 1024);
        if (caminho == null || !temAcesso()) {
            call.reject("sem acesso");
            return;
        }
        try {
            File f = new File(caminho).getCanonicalFile();
            String base = Environment.getExternalStorageDirectory().getCanonicalPath();
            if (!f.getPath().startsWith(base) || !ehLivro(f.getName())) {
                call.reject("arquivo nao permitido");
                return;
            }
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(off);
                byte[] buf = new byte[tam];
                int n = raf.read(buf);
                if (n < 0) n = 0;
                byte[] out = n == tam ? buf : java.util.Arrays.copyOf(buf, n);
                JSObject r = new JSObject();
                r.put("dados", Base64.encodeToString(out, Base64.NO_WRAP));
                r.put("fim", off + n >= raf.length());
                call.resolve(r);
            }
        } catch (Exception e) {
            call.reject("erro ao ler: " + e.getMessage());
        }
    }

    // ---------- Atualizacao do app ----------
    @PluginMethod
    public void instalar(PluginCall call) {
        final String url = call.getString("url");
        final Context ctx = getContext();
        if (url == null) {
            call.reject("sem url");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            call.reject("permissao");
            return;
        }
        new Thread(() -> {
            try {
                File dir = new File(ctx.getCacheDir(), "atualizacao");
                dir.mkdirs();
                File apk = new File(dir, "LeitorDeLivros.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
                long total = c.getContentLengthLong(), lido = 0;
                int ultimo = -1;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        lido += n;
                        if (total > 0) {
                            int pct = (int) (lido * 100 / total);
                            if (pct != ultimo) {
                                ultimo = pct;
                                JSObject e = new JSObject();
                                e.put("pct", pct);
                                notifyListeners("progresso", e);
                            }
                        }
                    }
                }
                Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                call.resolve();
            } catch (Exception e) {
                call.reject("download falhou: " + e.getMessage());
            }
        }).start();
    }

    // ---------- Manter a CPU acordada durante a leitura com tela apagada ----------
    @PluginMethod
    public void wakeLock(PluginCall call) {
        boolean ativo = Boolean.TRUE.equals(call.getBoolean("ativo", false));
        try {
            if (ativo) {
                if (wakeLock == null) {
                    PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leitor:leitura");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
            } else if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }
}
