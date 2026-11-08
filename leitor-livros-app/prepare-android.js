// Roda depois de "npx cap add android": instala o plugin nativo, permissoes, icone e versao.
const fs = require('fs'), path = require('path');
const A = 'android/app/src/main';
const vc = process.env.VERSION_CODE || '1', vn = process.env.VERSION_NAME || '1.0';

// 1) codigo Java
const pkg = path.join(A, 'java/com/touchcell/leitor');
fs.mkdirSync(pkg, {recursive: true});
fs.copyFileSync('native/LeitorNativoPlugin.java', path.join(pkg, 'LeitorNativoPlugin.java'));
fs.writeFileSync(path.join(pkg, 'MainActivity.java'), `package com.touchcell.leitor;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LeitorNativoPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
`);

// 2) icone da notificacao
fs.mkdirSync(path.join(A, 'res/drawable'), {recursive: true});
fs.writeFileSync(path.join(A, 'res/drawable/ic_stat_leitor.xml'), `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M18,2L6,2c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2L20,4c0,-1.1 -0.9,-2 -2,-2zM6,4h5v8l-2.5,-1.5L6,12L6,4z"/>
</vector>
`);

// 3) FileProvider (instalar a atualizacao)
fs.mkdirSync(path.join(A, 'res/xml'), {recursive: true});
fs.writeFileSync(path.join(A, 'res/xml/file_paths.xml'), `<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-path name="my_images" path="." />
    <cache-path name="my_cache_images" path="." />
    <files-path name="my_files" path="." />
</paths>
`);

// 4) AndroidManifest
const mp = path.join(A, 'AndroidManifest.xml');
let m = fs.readFileSync(mp, 'utf8');
const perms = [
  'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
  'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
  'android.permission.POST_NOTIFICATIONS', 'android.permission.WAKE_LOCK',
  'android.permission.REQUEST_INSTALL_PACKAGES', 'android.permission.MANAGE_EXTERNAL_STORAGE',
].filter(p => !m.includes(p)).map(p => `    <uses-permission android:name="${p}" />`).join('\n');
const leitura = m.includes('READ_EXTERNAL_STORAGE') ? '' : '\n    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />';
m = m.replace('<application', perms + leitura + '\n\n    <application');
m = m.replace('<application', '<application android:usesCleartextTraffic="true" android:requestLegacyExternalStorage="true"');
if (!m.includes('AndroidForegroundService')) {
  m = m.replace('</application>', `    <receiver android:name="io.capawesome.capacitorjs.plugins.foregroundservice.NotificationActionBroadcastReceiver" />
        <service android:name="io.capawesome.capacitorjs.plugins.foregroundservice.AndroidForegroundService" android:foregroundServiceType="mediaPlayback" />
    </application>`);
}
fs.writeFileSync(mp, m);

// 5) versao (usada pela atualizacao automatica)
const gp = 'android/app/build.gradle';
let g = fs.readFileSync(gp, 'utf8');
g = g.replace(/versionCode\s+\d+/, `versionCode ${vc}`).replace(/versionName\s+"[^"]*"/, `versionName "${vn}"`);
fs.writeFileSync(gp, g);
console.log('android preparado: versionCode', vc, vn);
