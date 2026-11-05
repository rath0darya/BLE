package com.anonvox.bleexplorer

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceRequest
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class WifiDirectFinding(val name:String,val address:String,val status:String,val services:List<String>,val cameraHint:Boolean)

class NearbyWifiDirectActivity:ComponentActivity(){
 private var manager:WifiP2pManager?=null
 private var channel:WifiP2pManager.Channel?=null
 private var receiverRegistered=false
 private val findings=linkedMapOf<String,WifiDirectFinding>()
 private var publish:((List<WifiDirectFinding>,String)->Unit)?=null
 private var serviceRequest:WifiP2pUpnpServiceRequest?=null
 private val receiver=object:BroadcastReceiver(){
  override fun onReceive(context:Context?,intent:Intent?){
   when(intent?.action){
    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION->requestPeers()
    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION->{
     val enabled=intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,WifiP2pManager.WIFI_P2P_STATE_DISABLED)==WifiP2pManager.WIFI_P2P_STATE_ENABLED
     publish?.invoke(findings.values.toList(),if(enabled)"Wi-Fi Direct enabled." else "Turn on Wi-Fi to use Wi-Fi Direct.")
    }
   }
  }
 }
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  manager=getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
  channel=manager?.initialize(this,mainLooper,null)
  setContent{WifiDirectScreen({finish()},{discover()},{cb->publish=cb})}
  val filter=IntentFilter().apply{addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)}
  try{if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)else registerReceiver(receiver,filter);receiverRegistered=true}catch(_:Exception){}
 }
 private fun allowed():Boolean=if(Build.VERSION.SDK_INT>=33)ContextCompat.checkSelfPermission(this,Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED else ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
 private fun discover(){
  if(!allowed()){
   val permission=if(Build.VERSION.SDK_INT>=33)Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
   requestPermissions(arrayOf(permission),912);publish?.invoke(findings.values.toList(),"Allow Nearby Wi-Fi devices permission to discover peers.");return
  }
  val m=manager;val ch=channel
  if(m==null||ch==null){publish?.invoke(emptyList(),"Wi-Fi Direct is unavailable on this device.");return}
  findings.clear();publish?.invoke(emptyList(),"Preparing Wi-Fi Direct UPnP discovery…")
  try{
   m.setUpnpServiceResponseListener(ch){services,device->
    val old=findings[device.deviceAddress]
    val all=(old?.services.orEmpty()+services).distinct()
    val hint=isCameraName(device.deviceName)||all.any{isCameraName(it)}
    findings[device.deviceAddress]=WifiDirectFinding(device.deviceName,device.deviceAddress,device.status.toString(),all,hint)
    runOnUiThread{publish?.invoke(findings.values.toList(),"Received "+all.size+" UPnP service entries.")}
   }
   beginUpnpDiscovery(m,ch,0)

  }catch(_:SecurityException){publish?.invoke(findings.values.toList(),"Wi-Fi permission is required.")}catch(_:Exception){publish?.invoke(findings.values.toList(),"Could not start Wi-Fi Direct discovery.")}
 }
 private fun beginUpnpDiscovery(m:WifiP2pManager,ch:WifiP2pManager.Channel,attempt:Int){
  try{
   m.clearServiceRequests(ch,object:WifiP2pManager.ActionListener{
    override fun onSuccess(){
     serviceRequest=WifiP2pUpnpServiceRequest.newInstance()
     m.addServiceRequest(ch,serviceRequest,object:WifiP2pManager.ActionListener{
      override fun onSuccess(){
       m.discoverServices(ch,object:WifiP2pManager.ActionListener{
        override fun onSuccess(){runOnUiThread{publish?.invoke(findings.values.toList(),"UPnP service discovery started. Waiting for nearby devices to advertise services…")}}
        override fun onFailure(reason:Int){retryUpnpIfBusy(m,ch,attempt,reason,"UPnP service discovery unavailable")}
       })
      }
      override fun onFailure(reason:Int){retryUpnpIfBusy(m,ch,attempt,reason,"UPnP service request unavailable")}
     })
    }
    override fun onFailure(reason:Int){retryUpnpIfBusy(m,ch,attempt,reason,"Could not reset UPnP discovery requests")}
   })
  }catch(_:SecurityException){runOnUiThread{publish?.invoke(findings.values.toList(),"Wi-Fi permission is required.")}}
  catch(_:Exception){runOnUiThread{publish?.invoke(findings.values.toList(),"Could not prepare UPnP service discovery.")}}
 }
 private fun retryUpnpIfBusy(m:WifiP2pManager,ch:WifiP2pManager.Channel,attempt:Int,reason:Int,message:String){
  if(reason==WifiP2pManager.BUSY&&attempt<2){
   runOnUiThread{publish?.invoke(findings.values.toList(),"Wi-Fi Direct is busy; retrying service discovery…")}
   try{m.stopPeerDiscovery(ch,object:WifiP2pManager.ActionListener{
    override fun onSuccess(){Handler(mainLooper).postDelayed({beginUpnpDiscovery(m,ch,attempt+1)},1200)}
    override fun onFailure(stopReason:Int){Handler(mainLooper).postDelayed({beginUpnpDiscovery(m,ch,attempt+1)},1500)}
   })}catch(_:Exception){Handler(mainLooper).postDelayed({beginUpnpDiscovery(m,ch,attempt+1)},1500)}
  }else runOnUiThread{publish?.invoke(findings.values.toList(),message+" (code "+reason+"). Wait a moment and retry.")}
 }
 private fun requestPeers(){
  if(!allowed())return
  try{manager?.requestPeers(channel){list->
   for(d in list.deviceList){
    val old=findings[d.deviceAddress]
    findings[d.deviceAddress]=WifiDirectFinding(d.deviceName,d.deviceAddress,d.status.toString(),old?.services.orEmpty(),isCameraName(d.deviceName)||old?.cameraHint==true)
   }
   runOnUiThread{publish?.invoke(findings.values.toList(),"Nearby Wi-Fi Direct peers: "+findings.size+". Advertised services appear when supported.")}
  }}catch(_:Exception){}
 }
 private fun isCameraName(value:String)=listOf("camera","cctv","ipcam","ipc-","nvr","dvr","hikvision","dahua","reolink","ezviz","imou","v380","yoosee","xmeye","tapo").any{value.contains(it,true)}
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==912&&grantResults.isNotEmpty()&&grantResults[0]==PackageManager.PERMISSION_GRANTED)discover()}
 override fun onDestroy(){if(receiverRegistered)try{unregisterReceiver(receiver)}catch(_:Exception){};try{if(allowed()&&serviceRequest!=null)manager?.removeServiceRequest(channel,serviceRequest,object:WifiP2pManager.ActionListener{override fun onSuccess(){};override fun onFailure(reason:Int){}})}catch(_:Exception){};publish=null;super.onDestroy()}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WifiDirectScreen(onBack:()->Unit,onDiscover:()->Unit,onPublish:((List<WifiDirectFinding>,String)->Unit)->Unit){
 var rows by remember{mutableStateOf(emptyList<WifiDirectFinding>())}
 var status by remember{mutableStateOf("Find Wi-Fi Direct peers and UPnP services independently of the connected router network.")}
 onPublish{items,message->rows=items;status=message}
 MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF5EEAD4),background=Color(0xFF07111F),surface=Color(0xFF111E2E),onSurface=Color(0xFFE5EEF8),onSurfaceVariant=Color(0xFF9FB0C4))){
  Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={TopAppBar(title={Column{Text("Nearby Wi-Fi devices",fontWeight=FontWeight.Bold);Text("Wi-Fi Direct · UPnP service discovery",style=MaterialTheme.typography.labelSmall)}},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}})}){padding->
   LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    item{Card(shape=RoundedCornerShape(20.dp)){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
     Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Wifi,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(8.dp));Text("Independent nearby radio discovery",fontSize=19.sp,fontWeight=FontWeight.Bold)}
     Text("Looks for Wi-Fi Direct peers and UPnP services advertised by nearby devices. It does not depend on the SSID of your connected router.")
     Button(onClick=onDiscover,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(8.dp));Text("Discover nearby Wi-Fi devices")}
     Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     Text("Peers: "+rows.size+" · Camera-name hints: "+rows.count{it.cameraHint},fontWeight=FontWeight.SemiBold)
    }}}
    if(rows.isEmpty())item{Text("No Wi-Fi Direct peers reported yet. Nearby devices must have Wi-Fi Direct enabled and be discoverable.")}
    items(rows,key={it.address}){d->Card(shape=RoundedCornerShape(16.dp)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
     Row(verticalAlignment=Alignment.CenterVertically){Icon(if(d.cameraHint)Icons.Default.CameraAlt else Icons.Default.Wifi,null,tint=if(d.cameraHint)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(8.dp));Text(d.name.ifBlank{"Unnamed Wi-Fi Direct device"},fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));if(d.cameraHint)Text("CAMERA HINT",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}
     Text("Peer address: "+d.address,style=MaterialTheme.typography.bodySmall)
     Text("Wi-Fi Direct status: "+d.status,style=MaterialTheme.typography.bodySmall)
     Text("Advertised UPnP services: "+d.services.size,style=MaterialTheme.typography.labelMedium)
     d.services.forEach{Text("• "+it,style=MaterialTheme.typography.bodySmall)}
     if(d.services.isEmpty())Text("No UPnP service description received. The peer name alone does not confirm CCTV.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}}
    item{Text("This mode finds only devices exposing Wi-Fi Direct discovery or supported UPnP advertisements. Many ordinary Wi-Fi cameras do not do this. It cannot enumerate devices on an unrelated router's private network.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
   }
  }
 }
}
