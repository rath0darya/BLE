package com.anonvox.bleexplorer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class WifiSignal(val ssid:String,val bssid:String,val rssi:Int,val frequency:Int,val security:String,val cameraHint:Boolean,val band:String,val channel:Int?,val securityAssessment:String)

class NearbyWifiActivity:ComponentActivity(){
 private val handler=Handler(Looper.getMainLooper())
 private var receiverRegistered=false
 private var refreshAction:(()->Unit)?=null
 private val receiver=object:BroadcastReceiver(){
  override fun onReceive(context:Context?,intent:Intent?){if(intent?.action==WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)readResults()}
 }
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  setContent{NearbyWifiScreen(onBack={finish()},onScan={scan()},onStatus={refreshAction=it},onExport={exportReport(it)})}
  try{
   val filter=IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
   if(Build.VERSION.SDK_INT>=33){registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)} else { @Suppress("DEPRECATION") registerReceiver(receiver,filter) }
   receiverRegistered=true
  }catch(_:Exception){}
 }

 private fun exportReport(rows:List<WifiSignal>){
  val json=org.json.JSONArray().apply{rows.forEach{r->put(org.json.JSONObject().apply{
   put("ssid",r.ssid);put("bssid",r.bssid);put("rssi_dbm",r.rssi);put("frequency_mhz",r.frequency);put("security_capabilities",r.security)
   put("camera_name_hint",r.cameraHint);put("band",r.band);put("channel",r.channel);put("security_assessment",r.securityAssessment);put("identity_note","SSID text matching is only a naming hint, not evidence that CCTV is present.")
  })}}.toString(2)
  val text=buildString{
   appendLine("BLE EXPLORER — NEARBY WI-FI ACCESS POINT REPORT")
   appendLine("Generated: "+java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date()))
   appendLine("Access points: "+rows.size);appendLine("CCTV-name hints: "+rows.count{it.cameraHint})
   appendLine("Scope: nearby broadcast access points visible to this phone; this does not enumerate clients behind routers.")
   rows.forEachIndexed{index,r->
    appendLine();appendLine("ACCESS POINT ${index+1}: ${r.ssid}")
    appendLine("BSSID: "+r.bssid);appendLine("Signal: "+r.rssi+" dBm");appendLine("Frequency: "+r.frequency+" MHz")
    appendLine("Security capabilities: "+r.security);appendLine("Security assessment: "+r.securityAssessment);appendLine("Band / channel: "+r.band+" / "+(r.channel?.toString()?: "Unavailable"));appendLine("Camera-name hint: "+r.cameraHint)
   }
   appendLine();appendLine("Limitations: SSID hints do not confirm a camera. Security assessment is based on broadcast capability flags only; it does not test passwords, router configuration or encryption implementation. Wi-Fi scan results are subject to Android permissions and scan throttling.")
  }
  ReportExporter.shareAll(this,"BLE Explorer nearby Wi-Fi report","ble-explorer-wifi",text,json)
 }

 private fun permitted():Boolean=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
 private fun locationEnabled():Boolean=try{
  val lm=getSystemService(Context.LOCATION_SERVICE) as LocationManager
  if(Build.VERSION.SDK_INT>=28)lm.isLocationEnabled else lm.isProviderEnabled(LocationManager.GPS_PROVIDER)||lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
 }catch(_:Exception){false}
 private fun scan(){
  if(!permitted()){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),811);refreshAction?.invoke();return}
  if(!locationEnabled()){refreshAction?.invoke();return}
  try{
   val wifi=getSystemService(Context.WIFI_SERVICE) as WifiManager
   val started=wifi.startScan()
   readResults()
   refreshAction?.invoke()
   if(!started)handler.postDelayed({readResults()},2500)
  }catch(_:SecurityException){refreshAction?.invoke()}catch(_:Exception){refreshAction?.invoke()}
 }
 private fun readResults(){
  try{
   if(!permitted())return
   val wifi=getSystemService(Context.WIFI_SERVICE) as WifiManager
   val list=wifi.scanResults.map{r->
    val s=r.SSID.orEmpty()
    val name=s.ifBlank{"Hidden network"}
    val lower=s.lowercase()
    val hint=listOf("cctv","camera","cam-","cam_","ipcam","ipc-","nvr","dvr","hikvision","dahua","ezviz","imou","reolink","v380","yoosee","xmeye","tapocam","tapo").any{lower.contains(it)}
    WifiSignal(name,r.BSSID.orEmpty(),r.level,r.frequency,r.capabilities.orEmpty(),hint,
     when(r.frequency){in 2400..2500->"2.4 GHz";in 4900..5900->"5 GHz";in 5950..7125->"6 GHz";else->"Unknown band"},
     when(r.frequency){2412,2417,2422,2427,2432,2437,2442,2447,2452,2457,2462,2467,2472->(r.frequency-2407)/5;2484->14;in 5000..5900->(r.frequency-5000)/5;in 5950..7125->(r.frequency-5950)/5;else->null},
     when{r.capabilities.contains("WEP",true)->"Weak · WEP";r.capabilities.contains("SAE",true)||r.capabilities.contains("WPA3",true)->"Strong · WPA3";r.capabilities.contains("WPA2",true)->"Good · WPA2";r.capabilities.contains("EAP",true)->"Enterprise · EAP";r.capabilities.contains("WPA",true)->"Legacy · WPA";else->"Open / unknown · verify security"})
   }.distinctBy{it.bssid}.sortedByDescending{it.rssi}
   latest=list
   runOnUiThread{refreshAction?.invoke()}
  }catch(_:SecurityException){}catch(_:Exception){}
 }
 @Volatile private var latest:List<WifiSignal> = emptyList()
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==811&&grantResults.isNotEmpty()&&grantResults[0]==PackageManager.PERMISSION_GRANTED)scan()}
 override fun onDestroy(){handler.removeCallbacksAndMessages(null);if(receiverRegistered)try{unregisterReceiver(receiver)}catch(_:Exception){};super.onDestroy()}
 @OptIn(ExperimentalMaterial3Api::class)
 @Composable private fun NearbyWifiScreen(onBack:()->Unit,onScan:()->Unit,onStatus:(()->Unit)->Unit,onExport:(List<WifiSignal>)->Unit){
  var rows by remember{mutableStateOf(emptyList<WifiSignal>())}
  var status by remember{mutableStateOf("Ready")}
  var scanning by remember{mutableStateOf(false)}
  var expanded by remember{mutableStateOf<String?>(null)}
  onStatus{rows=latest;scanning=false;status=if(!permitted())"Location permission required" else if(!locationEnabled())"Turn on Location services" else "Scan results updated"}
  MaterialTheme(colorScheme=if(isSystemInDarkTheme())darkColorScheme(primary=Color(0xFFFBDA95),secondary=Color(0xFFDAA96A),background=Color(0xFF231610),surface=Color(0xFF35241F),onSurface=Color(0xFFF1E7E2),onSurfaceVariant=Color(0xFFD2BDB1))else lightColorScheme(primary=Color(0xFF9D320C),secondary=Color(0xFFB8521D),background=Color(0xFFFFF7EF),surface=Color(0xFFFFFAF6),onSurface=Color(0xFF5A2D1D),onSurfaceVariant=Color(0xFF806457))){
   Scaffold(topBar={TopAppBar(title={Text("Wi-Fi scanner",fontWeight=FontWeight.Bold)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}},actions={IconButton(onClick={onExport(rows)},enabled=rows.isNotEmpty()){Icon(Icons.Default.Share,"Export")}})}){padding->
    LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
     item{Card(shape=RoundedCornerShape(20.dp)){Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Access points",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Text("${rows.size} found · ${rows.count{it.cameraHint}} camera-name hints",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(scanning)CircularProgressIndicator(Modifier.size(25.dp),strokeWidth=2.dp)}
      Button(onClick={scanning=true;status="Scanning…";onScan()},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(8.dp));Text("Scan nearby")}
      Text(status,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     }}}
     if(rows.isEmpty())item{EmptyToolState("No access points yet","Enable Wi-Fi and Location, then scan.")}
     items(rows,key={it.bssid}){r->Card(shape=RoundedCornerShape(16.dp),onClick={expanded=if(expanded==r.bssid)null else r.bssid}){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Icon(if(r.cameraHint)Icons.Default.CameraAlt else Icons.Default.Wifi,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f)){Text(r.ssid,fontWeight=FontWeight.SemiBold,maxLines=1);Text(r.bssid,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("${r.rssi} dBm",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelLarge)}
      Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){InfoBadge(r.band);InfoBadge(r.securityAssessment.substringBefore(" · "));if(r.cameraHint)InfoBadge("Camera hint")}
      if(expanded==r.bssid){
       HorizontalDivider()
       Text("Frequency: ${r.frequency} MHz · Channel: ${r.channel?.toString()?: "Unavailable"}",style=MaterialTheme.typography.bodySmall)
       Text("Security: ${r.security.ifBlank{"Unknown"}}",style=MaterialTheme.typography.bodySmall)
       Text("Assessment: ${r.securityAssessment}",style=MaterialTheme.typography.bodySmall)
       if(r.cameraHint)Text("SSID contains a camera-related name only; this does not confirm CCTV.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
      }
     }}}
     item{Text("Nearby access points only · router-connected clients are not exposed by Android Wi-Fi scanning.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
   }
  }
 }

}
 
@Composable private fun EmptyToolState(title:String,subtitle:String){
 Card(shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)){Icon(Icons.Default.SearchOff,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(34.dp));Text(title,fontWeight=FontWeight.Bold);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}
}

@Composable private fun InfoBadge(label:String){Surface(shape=RoundedCornerShape(50),color=MaterialTheme.colorScheme.secondaryContainer){Text(label,Modifier.padding(horizontal=10.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSecondaryContainer)}}
