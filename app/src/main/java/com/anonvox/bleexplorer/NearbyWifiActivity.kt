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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class WifiSignal(val ssid:String,val bssid:String,val rssi:Int,val frequency:Int,val security:String,val cameraHint:Boolean)

class NearbyWifiActivity:ComponentActivity(){
 private val handler=Handler(Looper.getMainLooper())
 private var receiverRegistered=false
 private var refreshAction:(()->Unit)?=null
 private val receiver=object:BroadcastReceiver(){
  override fun onReceive(context:Context?,intent:Intent?){if(intent?.action==WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)readResults()}
 }
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  setContent{NearbyWifiScreen(onBack={finish()},onScan={scan()},onStatus={refreshAction=it})}
  try{
   val filter=IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
   if(Build.VERSION.SDK_INT>=33){registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)} else { @Suppress("DEPRECATION") registerReceiver(receiver,filter) }
   receiverRegistered=true
  }catch(_:Exception){}
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
    WifiSignal(name,r.BSSID.orEmpty(),r.level,r.frequency,r.capabilities.orEmpty(),hint)
   }.distinctBy{it.bssid}.sortedByDescending{it.rssi}
   latest=list
   runOnUiThread{refreshAction?.invoke()}
  }catch(_:SecurityException){}catch(_:Exception){}
 }
 @Volatile private var latest:List<WifiSignal> = emptyList()
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==811&&grantResults.isNotEmpty()&&grantResults[0]==PackageManager.PERMISSION_GRANTED)scan()}
 override fun onDestroy(){handler.removeCallbacksAndMessages(null);if(receiverRegistered)try{unregisterReceiver(receiver)}catch(_:Exception){};super.onDestroy()}
 @Composable private fun NearbyWifiScreen(onBack:()->Unit,onScan:()->Unit,onStatus:(()->Unit)->Unit){
  var rows by remember{mutableStateOf(emptyList<WifiSignal>())}
  var status by remember{mutableStateOf("Tap scan to inspect Wi-Fi access points broadcasting nearby.")}
  var scanning by remember{mutableStateOf(false)}
  onStatus{rows=latest;scanning=false;status=if(!permitted())"Location permission is required by Android to read nearby Wi-Fi scan results." else if(!locationEnabled())"Turn on Location services and scan again." else "Showing nearby Wi-Fi access points. Camera matches are only SSID-name hints."}
  MaterialTheme(colorScheme=darkColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF5EEAD4))){
   Scaffold(topBar={TopAppBar(title={Column{Text("Nearby Wi-Fi signals",fontWeight=FontWeight.Bold);Text("Access points · not client devices",style=MaterialTheme.typography.labelSmall)}},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}})}){padding->
    LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
     item{Card(shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
      Text("Nearby radio discovery",fontSize=20.sp,fontWeight=FontWeight.Bold)
      Text("Scans Wi-Fi access points visible to this phone, even when they are not on your connected network. It cannot see devices connected behind those access points.")
      Button(onClick={scanning=true;status="Requesting Wi-Fi scan…";onScan()},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(8.dp));Text("Scan nearby Wi-Fi")}
      Text(status,style=MaterialTheme.typography.bodySmall)
      Text("Access points: ${rows.size} · CCTV-name hints: ${rows.count{it.cameraHint}}",fontWeight=FontWeight.SemiBold)
     }}}
     if(rows.isEmpty())item{Text("No scan results available yet. Enable Wi-Fi and Location, then scan again. Android may throttle repeated Wi-Fi scans.")}
     items(rows,key={it.bssid}){r->Card(shape=RoundedCornerShape(16.dp)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Icon(if(r.cameraHint)Icons.Default.CameraAlt else Icons.Default.Wifi,null);Spacer(Modifier.width(8.dp));Text(r.ssid,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));if(r.cameraHint)Text("CAMERA HINT",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall)}
      Text("BSSID: ${r.bssid}",style=MaterialTheme.typography.bodySmall)
      Text("Signal: ${r.rssi} dBm · Frequency: ${r.frequency} MHz",style=MaterialTheme.typography.bodySmall)
      Text("Security: ${r.security.ifBlank{"Unknown"}}",style=MaterialTheme.typography.bodySmall)
      if(r.cameraHint)Text("The network name contains a camera-related term. This is only a clue, not confirmation that CCTV is present.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
     }}}
     item{Text("Important: Wi-Fi scans reveal nearby access points, not the phones/cameras connected to them. A camera using an ordinary router SSID or wired Ethernet cannot be identified this way. Android also restricts scan frequency and results.",style=MaterialTheme.typography.labelSmall)}
    }
   }
  }
 }
}
