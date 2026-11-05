package com.anonvox.bleexplorer
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

private data class BmsSnapshot(val status:String="Not connected",val batteryLevel:Int?=null,val characteristics:List<String> = emptyList())
private data class Device(val address:String,val name:String,val category:String,val transport:String,val rssi:Int,val seen:Long,val first:Long,val tx:Int?,val services:List<String>,val manufacturer:String,val serviceData:String,val raw:String,val history:List<Int>) {
 val quality get()=when { rssi>=-55->"Very strong";rssi>=-68->"Strong";rssi>=-80->"Moderate";else->"Weak" }
 val tint get()=when { rssi>=-55->Color(0xFF22C55E);rssi>=-68->Color(0xFF84CC16);rssi>=-80->Color(0xFFF59E0B);else->Color(0xFFEF4444) }
}
class MainActivity:ComponentActivity() {
 private val handler=Handler(Looper.getMainLooper()); private var scanner:BluetoothLeScanner?=null;private var callback:ScanCallback?=null;private var active=false
 private val found=linkedMapOf<String,Device>();private val knownNames=mutableMapOf<String,String>();private var update:((List<Device>)->Unit)?=null;private var statusUpdate:((Boolean,String)->Unit)?=null
 private val timeout=Runnable{stop("Scan complete")};private var bmsGatt:BluetoothGatt?=null;private var bmsUpdate:((BmsSnapshot)->Unit)?=null
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{
  var devices by remember{mutableStateOf(emptyList<Device>())};var scanning by remember{mutableStateOf(false)};var status by remember{mutableStateOf("Ready when you are")};var bms by remember{mutableStateOf(BmsSnapshot())}
  update={devices=it};statusUpdate={a,s->scanning=a;status=s};bmsUpdate={bms=it}
  AppTheme { Screen(devices,scanning,status,{requestScan()},{stop("Scan stopped")},{share(devices)},{device->pairDevice(device)},{device->connectBms(device)},bms,{startActivity(Intent(this,NetworkAuditActivity::class.java))},{startActivity(Intent(this,NearbyWifiActivity::class.java))}) }
 }}
 private fun requiredPermissions():Array<String> = if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.ACCESS_FINE_LOCATION) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
 private fun allowed():Boolean = requiredPermissions().all{ContextCompat.checkSelfPermission(this,it)==PackageManager.PERMISSION_GRANTED}
 private fun locationReady():Boolean { if(Build.VERSION.SDK_INT>=31)return true; return try { (getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager).let { it.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || it.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) } } catch(_:Exception){false} }
 private fun requestScan(){if(!allowed()){requestPermissions(requiredPermissions(),701);statusUpdate?.invoke(false,"Allow Nearby devices and Location permissions to discover Bluetooth devices");return};if(!locationReady()){statusUpdate?.invoke(false,"Turn on Location services, then tap Scan again");try{startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))}catch(_:Exception){};return};start()}
 @Deprecated("Android permission callback")
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==701){if(grantResults.isNotEmpty()&&grantResults.all{it==PackageManager.PERMISSION_GRANTED})requestScan() else statusUpdate?.invoke(false,"Nearby devices and Location permissions are required to scan")}}
 private fun start(){if(active)return;try{
  val adapter=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
  if(adapter==null){statusUpdate?.invoke(false,"Bluetooth is not supported");return};if(!adapter.isEnabled){statusUpdate?.invoke(false,"Turn on Bluetooth, then try again");return}
  scanner=adapter.bluetoothLeScanner;if(scanner==null){statusUpdate?.invoke(false,"Bluetooth scanner unavailable");return}
  found.clear();update?.invoke(emptyList())
  callback=object:ScanCallback(){override fun onScanResult(type:Int,result:ScanResult){add(result)};override fun onBatchScanResults(results:MutableList<ScanResult>){results.forEach{add(it)}};override fun onScanFailed(code:Int){runOnUiThread{stop("Scan failed ($code). Try again.")}}}
  scanner?.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT).setReportDelay(0L).build(),callback)
  active=true;statusUpdate?.invoke(true,"Scanning nearby devices…");handler.removeCallbacks(timeout);handler.postDelayed(timeout,30000)
 }catch(_:SecurityException){statusUpdate?.invoke(false,"Bluetooth permission was denied")}catch(_:Exception){statusUpdate?.invoke(false,"Could not start scanning")}}
 private fun add(r:ScanResult){
  val rec=r.scanRecord
  val addr=try{r.device.address?:"Unavailable"}catch(_:SecurityException){"Hidden by Android"}
  val services=rec?.serviceUuids?.map{it.uuid.toString()}?:emptyList()
  val m=rec?.manufacturerSpecificData?.let{s->buildString{for(i in 0 until s.size()){if(i>0)append(" · ");append("ID ");append(Integer.toHexString(s.keyAt(i)));append(": ");s.valueAt(i).forEach{b->append(String.format(Locale.US,"%02X",b.toInt() and 0xFF))}}}}.orEmpty()
  val sd=rec?.serviceData?.entries?.joinToString(" · "){entry -> buildString{append(entry.key.uuid);append(": ");entry.value.forEach{b->append(String.format(Locale.US,"%02X",b.toInt() and 0xFF))}}}.orEmpty()
  val raw=rec?.bytes?.take(64)?.joinToString(" "){byte -> String.format(Locale.US,"%02X",byte.toInt() and 0xFF)}.orEmpty()
  val transport=try{when(r.device.type){BluetoothDevice.DEVICE_TYPE_LE->"Bluetooth Low Energy (LE)";BluetoothDevice.DEVICE_TYPE_CLASSIC->"Bluetooth Classic (BR/EDR)";BluetoothDevice.DEVICE_TYPE_DUAL->"Dual-mode (LE + Classic BR/EDR)";else->"Unknown — Android has not identified the transport"}}catch(_:SecurityException){"Unknown — Bluetooth permission required"}
  val advertisedName=rec?.deviceName?.takeIf{it.isNotBlank()}?:extractAdvertisedName(rec?.bytes)
  val cachedName=try{r.device.name?.takeIf{it.isNotBlank()}}catch(_:SecurityException){null}
  val now=System.currentTimeMillis()
  synchronized(found){
   val old=found[addr]
   // Keep a previously learned name: later packets from the same device may omit the name field.
   // Retain the first valid name for this address; later BLE packets often omit it.
   val retainedName=knownNames[addr]?:old?.name?.takeIf{it.isNotBlank()&&it!="Unnamed device"}
   val name=retainedName?:advertisedName?:cachedName?:"Unnamed device"
   if(name!="Unnamed device")knownNames[addr]=name
   val category=classifyDevice(name,services,m,sd)
   found[addr]=Device(addr,name,category,transport,r.rssi,now,old?.first?:now,rec?.txPowerLevel,services,m,sd,raw,((old?.history?:emptyList())+r.rssi).takeLast(20))
   val list=found.values.sortedByDescending{it.rssi}
   runOnUiThread{update?.invoke(list)}
  }
 }
 private fun connectBms(device:Device){
  if(!allowed()){requestPermissions(requiredPermissions(),701);Toast.makeText(this,"Allow Nearby devices permission to connect",Toast.LENGTH_LONG).show();return}
  try{
   bmsGatt?.close();bmsGatt=null;bmsUpdate?.invoke(BmsSnapshot("Connecting to "+device.name+"…"))
   val remote=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.getRemoteDevice(device.address)
   if(remote==null){bmsUpdate?.invoke(BmsSnapshot("Device unavailable"));return}
   bmsGatt=remote.connectGatt(this,false,object:BluetoothGattCallback(){
    override fun onConnectionStateChange(gatt:BluetoothGatt,status:Int,newState:Int){
     if(status!=BluetoothGatt.GATT_SUCCESS){runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Connection failed (GATT "+status+")"))};gatt.close();return}
     if(newState==BluetoothProfile.STATE_CONNECTED){runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Connected · discovering BMS services"))};gatt.discoverServices()}
     else if(newState==BluetoothProfile.STATE_DISCONNECTED){runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Disconnected"))};gatt.close();if(bmsGatt===gatt)bmsGatt=null}
    }
    override fun onServicesDiscovered(gatt:BluetoothGatt,status:Int){
     if(status!=BluetoothGatt.GATT_SUCCESS){runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Connected · service discovery failed"))};return}
     val rows=mutableListOf<String>();var battery:BluetoothGattCharacteristic?=null
     for(service in gatt.services){
      rows.add("SERVICE  "+service.uuid)
      for(ch in service.characteristics){
       val p=mutableListOf<String>()
       if(ch.properties and BluetoothGattCharacteristic.PROPERTY_READ!=0)p.add("Read")
       if(ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE!=0)p.add("Write")
       if(ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE!=0)p.add("Write NR")
       if(ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY!=0)p.add("Notify")
       if(ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE!=0)p.add("Indicate")
       rows.add(ch.uuid.toString()+" · "+p.joinToString("/").ifBlank{"No standard access flags"})
       if(ch.uuid.toString().equals("00002a19-0000-1000-8000-00805f9b34fb",true))battery=ch
      }
     }
     runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Connected · "+gatt.services.size+" services discovered",characteristics=rows))}
     battery?.let{try{gatt.readCharacteristic(it)}catch(_:SecurityException){}}
    }
    @Deprecated("Deprecated Android callback")
    override fun onCharacteristicRead(gatt:BluetoothGatt,characteristic:BluetoothGattCharacteristic,status:Int){
     if(status==BluetoothGatt.GATT_SUCCESS&&characteristic.uuid.toString().equals("00002a19-0000-1000-8000-00805f9b34fb",true)){
      val level=characteristic.value?.firstOrNull()?.toInt()?.and(0xFF)
      runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Connected · "+gatt.services.size+" services discovered",level,gatt.services.flatMap{sv->listOf("SERVICE  "+sv.uuid)+sv.characteristics.map{it.uuid.toString()}}))}
     }
    }
   })
  }catch(_:SecurityException){bmsUpdate?.invoke(BmsSnapshot("Bluetooth permission required"))}catch(_:Exception){bmsUpdate?.invoke(BmsSnapshot("Could not connect to this device"))}
 }
 private fun pairDevice(device:Device){
  if(!allowed()){requestPermissions(requiredPermissions(),701);Toast.makeText(this,"Allow Bluetooth permissions before pairing",Toast.LENGTH_LONG).show();return}
  try{
   val adapter=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
   val remote=adapter?.getRemoteDevice(device.address)
   if(remote==null){Toast.makeText(this,"Bluetooth device is unavailable",Toast.LENGTH_LONG).show();return}
   when(remote.bondState){
    BluetoothDevice.BOND_BONDED->Toast.makeText(this,"Device is already paired",Toast.LENGTH_SHORT).show()
    BluetoothDevice.BOND_BONDING->Toast.makeText(this,"Pairing is already in progress",Toast.LENGTH_SHORT).show()
    else->{val started=remote.createBond();Toast.makeText(this,if(started)"Android pairing requested. Complete the system pairing prompt." else "Android could not start pairing.",Toast.LENGTH_LONG).show()}
   }
  }catch(_:SecurityException){Toast.makeText(this,"Bluetooth permission is required to pair",Toast.LENGTH_LONG).show()}
   catch(_:IllegalArgumentException){Toast.makeText(this,"Invalid or unavailable Bluetooth address",Toast.LENGTH_LONG).show()}
   catch(_:Exception){Toast.makeText(this,"Could not start Bluetooth pairing",Toast.LENGTH_LONG).show()}
 }
 private fun stop(message:String){handler.removeCallbacks(timeout);if(active){try{scanner?.stopScan(callback)}catch(_:SecurityException){};active=false};statusUpdate?.invoke(false,message)}
 private fun share(ds:List<Device>){val csv=buildString{appendLine("Name,Category,Address,RSSI,Signal,First seen,Last seen,Services,Manufacturer data,Service data,Raw advertisement");ds.forEach{d->appendLine(listOf(d.name,d.category,d.address,d.rssi.toString(),d.quality,date(d.first),date(d.seen),d.services.joinToString(";"),d.manufacturer,d.serviceData,d.raw).joinToString(","){ "\""+it.replace("\"","\"\"")+"\"" })}}
  startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/csv";putExtra(Intent.EXTRA_SUBJECT,"BLE Explorer results");putExtra(Intent.EXTRA_TEXT,csv)},"Share scan results"))
 }
 override fun onDestroy(){stop("App closed");try{bmsGatt?.disconnect();bmsGatt?.close()}catch(_:Exception){};bmsGatt=null;update=null;statusUpdate=null;bmsUpdate=null;super.onDestroy()}
}
/** Reads Complete Local Name (0x09) and Shortened Local Name (0x08) AD structures.
 * ScanRecord.deviceName is preferred, but parsing the advertisement is a safe fallback.
 */
private fun extractAdvertisedName(bytes:ByteArray?):String? {
 if(bytes==null)return null
 var index=0
 while(index<bytes.size){
  val length=bytes[index].toInt() and 0xFF
  if(length==0)break
  val end=index+1+length
  if(end>bytes.size)break
  val type=bytes[index+1].toInt() and 0xFF
  if((type==0x08||type==0x09)&&length>1){
   val value=String(bytes,index+2,length-1,Charsets.UTF_8).trim().trim('\u0000')
   if(value.isNotBlank())return value
  }
  index=end
 }
 return null
}

private fun classifyDevice(name:String,services:List<String>,manufacturer:String,serviceData:String):String {
 val n=(name+" "+manufacturer+" "+serviceData).lowercase(Locale.ROOT)
 val u=services.map{raw->val compact=raw.replace("-","").lowercase(Locale.ROOT);if(compact.length==32)compact.substring(4,8) else compact}
 fun has(vararg ids:String)=u.any{v->ids.any{v==it}}
 fun named(vararg words:String)=words.any{n.contains(it)}
 return when {
  named("ev battery","ev-battery","traction battery","battery management system","battery pack","bms","lifepo","li-fe-po","lithium battery","e-scooter battery","electric scooter battery","electric vehicle battery","e-bike battery","ebike battery","inverter battery","bms_","bms-","jbd","dal y bms","jk-bms","seplos","ant bms","pace bms","scooter bms") -> "EV Batteries"
  named("cctv","ip camera","security camera","surveillance","network camera","camera_","camera-","cam_","cam-","nvr","dvr","reolink","arlo","wyze","eufy","ring camera","hikvision","dahua","ezviz","imou","uniview","axis camera","amcrest","tapocam","tapo c","v380","yoosee","xmeye") -> "CCTV Cameras"
  named("airpods","earbuds","ear bud","headphone","headset","neckband","soundbar","speaker","galaxy buds","pixel buds","wf-","wh-1000","boAt","boat ","jbl","bose","sony audio","beats ") || has("110b","110d","110e","110f","111e","111f","1124","1130","1131") -> "Audio"
  named("iphone","ipad","android phone","samsung galaxy","galaxy s","galaxy note","pixel phone","oneplus","xiaomi","redmi","realme","vivo ","oppo ","motorola","nothing phone","huawei phone","honor phone") -> "Phones"
  named("apple watch","galaxy watch","pixel watch","fitbit","garmin","smartwatch","smart watch","fitness band","mi band","miband","fitness tracker","heart rate","wearable","watch ") || has("180d","1816","1818","1819","1822","183a") -> "Wearables"
  named("airtag","tile","smarttag","tracker","find my","ibeacon","eddystone","beacon","chipolo","pebblebee") || has("feaa","feab") -> "Trackers"
  named("obd","tpms","tire pressure","tyre pressure","vehicle","motorcycle","car diagnostic","elm327","vlinker","scooter","ebike","e-bike") -> "Automotive"
  named("smart bulb","smart light","smart plug","smart switch","thermostat","homekit","smart home","zigbee","matter","hue","lifx","tuya","sonoff","aqara") || has("fd6f","fcf1") -> "Smart Home"
  named("keyboard","mouse","trackpad","gamepad","controller","joystick","hid") || has("1812") -> "Input Devices"
  named("laptop","notebook","desktop","macbook","thinkpad","surface pro","chromebook","windows pc") -> "Computers"
  named("printer","label maker","thermal printer","barcode scanner") -> "Printers"
  named("glucose","glucometer","pulse oximeter","blood pressure","medical","hearing aid","insulin") || has("1808","1810","181a","1821") -> "Medical"
  named("battery","batt ","batt_","batt-","power bank","ups battery","lithium pack","lifepo4","li-ion","li ion","bms") -> "Batteries"
  else -> "Unknown"
 }
}
@Composable private fun AppTheme(content: @Composable () -> Unit){val dark=isSystemInDarkTheme();MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Color(0xFF7DD3FC),secondary=Color(0xFFA5B4FC),background=Color(0xFF0A1020),surface=Color(0xFF121B2D),surfaceVariant=Color(0xFF202B40),onSurface=Color(0xFFF1F5F9),onSurfaceVariant=Color(0xFFB8C5D8))else lightColorScheme(primary=Color(0xFF0369A1),secondary=Color(0xFF4F46E5),background=Color(0xFFF4F7FC),surface=Color.White,surfaceVariant=Color(0xFFE8EEF7),onSurface=Color(0xFF102033),onSurfaceVariant=Color(0xFF52647B)),content=content)}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Screen(devices:List<Device>,active:Boolean,status:String,onScan:()->Unit,onStop:()->Unit,onShare:()->Unit,onPair:(Device)->Unit,onConnectBms:(Device)->Unit,bms:BmsSnapshot,onNetworkAudit:()->Unit,onNearbyWifi:()->Unit){
 var compact by remember{mutableStateOf(false)};var query by remember{mutableStateOf("")};var filter by remember{mutableStateOf("Any signal")};var selected by remember{mutableStateOf<Device?>(null)};var settings by remember{mutableStateOf(false)}
 val filters=listOf("Any signal","Strong","Weak","Named","Services","Recent")
 val shown=devices.filter{(it.name.contains(query,true)||it.address.contains(query,true))&&when(filter){"Strong"->it.rssi>=-68;"Weak"->it.rssi < -80;"Named"->it.name!="Unnamed device";"Services"->it.services.isNotEmpty();"Recent"->System.currentTimeMillis()-it.seen<5000;else->true}}
 Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={TopAppBar(title={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){
  Box(Modifier.size(43.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF38BDF8),Color(0xFF6366F1)))),contentAlignment=Alignment.Center){Icon(Icons.Default.BluetoothSearching,null,tint=Color.White,modifier=Modifier.size(26.dp))}
  Column{Text("BLE",fontWeight=FontWeight.Bold,fontSize=22.sp);Text("Bluetooth discovery",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
 }},actions={IconButton(onClick={settings=true}){Icon(Icons.Default.Settings,"Settings")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))}){pad->
 LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
  item{Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(if(isSystemInDarkTheme())listOf(Color(0xFF162B48),Color(0xFF28244F),Color(0xFF122B3D))else listOf(Color(0xFFE0F2FE),Color(0xFFEDE9FE),Color(0xFFE0F7FA)))).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(if(active)"Searching nearby" else "Discover your surroundings",fontSize=21.sp,fontWeight=FontWeight.Bold);Text(if(active)"Listening for Bluetooth broadcasts" else "Find nearby devices without pairing",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.SignalCellularAlt,null,modifier=Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary)}
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("FOUND",devices.size.toString(),Icons.Default.BluetoothSearching,Modifier.weight(1f));Metric("STRONGEST",devices.maxByOrNull{it.rssi}?.let{"${it.rssi} dBm"}?:"—",Icons.Default.SignalCellularAlt,Modifier.weight(1f));Metric("STATUS",if(active)"Live" else "Ready",Icons.Default.CheckCircle,Modifier.weight(1f))}
   Button(onClick=if(active)onStop else onScan,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(17.dp),colors=ButtonDefaults.buttonColors(containerColor=if(active)Color(0xFFDC2626)else MaterialTheme.colorScheme.primary)){Icon(if(active)Icons.Default.Stop else Icons.Default.Refresh,null);Spacer(Modifier.width(8.dp));Text(if(active)"Stop scanning" else if(devices.isEmpty())"Start scan" else "Scan again",fontWeight=FontWeight.Bold)}
   Text(status,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }}
  item{OutlinedButton(onClick=onNetworkAudit,modifier=Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(15.dp)){Icon(Icons.Default.Security,null);Spacer(Modifier.width(8.dp));Text("Network & CCTV security audit")}}
  item{OutlinedButton(onClick=onNearbyWifi,modifier=Modifier.fillMaxWidth().height(48.dp),shape=RoundedCornerShape(15.dp)){Icon(Icons.Default.Wifi,null);Spacer(Modifier.width(8.dp));Text("Nearby Wi-Fi camera signals")}}
  item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(query,{query=it},modifier=Modifier.weight(1f),placeholder={Text("Search devices")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true,shape=RoundedCornerShape(16.dp));FilledTonalIconButton(onClick={compact=!compact},modifier=Modifier.size(56.dp)){Icon(if(compact)Icons.Default.GridView else Icons.Default.List,"Switch layout")};FilledTonalIconButton(onClick=onShare,modifier=Modifier.size(56.dp),enabled=devices.isNotEmpty()){Icon(Icons.Default.Share,"Share results")}}}
  item{Column(verticalArrangement=Arrangement.spacedBy(7.dp)){Text("REFINE RESULTS",style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurfaceVariant);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){filters.forEach{f->FilterChip(selected=filter==f,onClick={filter=f},label={Text(f)})}}}}
  item{Column{Text("Nearby devices",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("${shown.size} devices · sorted by signal",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
  if(shown.isEmpty())item{Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surface).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Default.BluetoothSearching,null,modifier=Modifier.size(44.dp),tint=MaterialTheme.colorScheme.primary);Text(if(devices.isEmpty())"No devices found yet" else "No matching devices",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium);Text(if(devices.isEmpty())"Tap Start scan. It stops automatically after 30 seconds." else "Try another search or filter.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}}
  else items(shown,key={it.address}){d->DeviceCard(d,compact){selected=d}}
  item{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){Icon(Icons.Default.Info,null,modifier=Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Text("Each discovered device is labelled with its detected type. BLE broadcasts vary by device; some products remain invisible unless they advertise.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
 }}
 selected?.let{device->DeviceSheet(device,{onPair(device)},{onConnectBms(device)},bms){selected=null}}
 if(settings)AlertDialog(onDismissRequest={settings=false},title={Text("Scan settings")},text={Text("BLE asks for Nearby devices and Location permission before scanning. On older Android versions, Location services must also be switched on. Scans start only when you tap the button and stop after 30 seconds. Pairing is initiated only when you choose Pair securely in a device's details.")},confirmButton={TextButton(onClick={settings=false}){Text("Got it")}})
}
@Composable private fun Metric(label:String,value:String,icon:ImageVector,modifier:Modifier=Modifier){Column(modifier.clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha=.72f)).padding(horizontal=9.dp,vertical=11.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(17.dp));Text(value,fontWeight=FontWeight.Bold,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(label,fontSize=9.sp,letterSpacing=1.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=FontWeight.Bold)}}
@Composable private fun DeviceCard(d:Device,compact:Boolean,click:()->Unit){Surface(Modifier.fillMaxWidth().clickable(onClick=click),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=3.dp,shadowElevation=if(isSystemInDarkTheme())2.dp else 7.dp,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=if(isSystemInDarkTheme()).28f else .18f))){
 if(compact)Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){Glyph(d,44);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(d.name,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(d.address,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)};Column(horizontalAlignment=Alignment.End){Text("${d.rssi} dBm",fontWeight=FontWeight.Bold,color=d.tint,fontSize=13.sp);Text(d.quality,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.ChevronRight,null)}
 else Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Glyph(d,52);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(d.name,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(d.name=="Unnamed device")"Name not shared in broadcast" else "Bluetooth Low Energy device",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)}
 Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(d.quality,fontWeight=FontWeight.SemiBold,color=d.tint);Text("Signal strength",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("${d.rssi} dBm",fontWeight=FontWeight.Bold,fontSize=18.sp,color=d.tint)}
 Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)){Box(Modifier.fillMaxWidth(((d.rssi+100).coerceIn(0,70)/70f)).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(d.tint.copy(alpha=.65f),d.tint))))}
 Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){Tag(d.category.uppercase(Locale.ROOT));Tag("SEEN ${ago(d.seen)}");if(d.services.isNotEmpty())Tag("${d.services.size} SERVICES");d.tx?.let{Tag("TX $it dBm")}}
 }}
}
@Composable private fun Glyph(d:Device,size:Int){val palettes=listOf(listOf(Color(0xFF0EA5E9),Color(0xFF6366F1)),listOf(Color(0xFF10B981),Color(0xFF0D9488)),listOf(Color(0xFFF59E0B),Color(0xFFEA580C)));Box(Modifier.size(size.dp).clip(RoundedCornerShape(17.dp)).background(Brush.linearGradient(palettes[kotlin.math.abs(d.name.hashCode()%palettes.size)])),contentAlignment=Alignment.Center){Icon(Icons.Default.BluetoothSearching,null,tint=Color.White,modifier=Modifier.size((size*.52).dp))}}
@Composable private fun Tag(s:String){Text(s,Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=8.dp,vertical=5.dp),fontSize=9.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DeviceSheet(d:Device,onPair:()->Unit,onConnectBms:()->Unit,bms:BmsSnapshot,close:()->Unit){ModalBottomSheet(onDismissRequest=close,containerColor=MaterialTheme.colorScheme.surface){Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Glyph(d,58);Column(Modifier.weight(1f)){Text(d.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("${d.category} · Detected Bluetooth broadcast",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}}
 Button(onClick=onPair,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(15.dp)){Icon(Icons.Default.Lock,null);Spacer(Modifier.width(8.dp));Text("Pair securely",fontWeight=FontWeight.Bold)}
 Text("PAIRING FORMAT & COMPATIBILITY",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
 Detail("Detected Bluetooth transport",d.transport)
 Detail("Pairing protocol","BLE devices use SMP when pairing is supported. Classic Bluetooth uses BR/EDR pairing.")
 Detail("Possible pairing methods","Just Works, Passkey Entry, Numeric Comparison or Out-of-Band (OOB) — selected during pairing based on both devices.")
 Detail("LE Secure Connections","Not confirmed by scan. If both devices support it, BLE pairing can use ECDH P-256.")
 Detail("Pairing availability","Not guaranteed by discovery. Some devices only broadcast and do not accept pairing.")
 Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("SIGNAL","${d.rssi} dBm",Icons.Default.SignalCellularAlt,Modifier.weight(1f));Metric("QUALITY",d.quality,Icons.Default.CheckCircle,Modifier.weight(1f))}
 if(d.category=="EV Batteries"||d.category=="Batteries"){
  Text("EV BATTERY · BMS MONITOR",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)).padding(13.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Text(bms.status,style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Metric("BATTERY LEVEL",bms.batteryLevel?.let{it.toString()+"%"}?:"—",Icons.Default.BatteryFull,Modifier.weight(1f))
    Metric("GATT SERVICES",bms.characteristics.count{it.startsWith("SERVICE")}.toString(),Icons.Default.SettingsBluetooth,Modifier.weight(1f))
   }
   Button(onClick=onConnectBms,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.BluetoothConnected,null);Spacer(Modifier.width(7.dp));Text("Connect & read BMS data")}
   Text("LIVE TELEMETRY",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
   listOf("Pack voltage","Charge / discharge current","State of charge (SOC)","State of health (SOH)","Remaining / full capacity","Cell voltage min / max","Cell voltage difference","Cell-by-cell voltages","Battery temperature sensors","Charge / discharge MOSFET","Cycle count","Protection & fault alarms").forEach{label->Detail(label,"Not exposed by this device's currently decoded GATT data")}
   Text("The standard Bluetooth Battery Service can provide a percentage only. Pack and cell telemetry requires a supported BMS vendor protocol or documented GATT characteristics. Raw service and characteristic UUIDs below help identify compatible hardware.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(bms.characteristics.isNotEmpty()){Text("DISCOVERED GATT CHARACTERISTICS",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelSmall);bms.characteristics.take(60).forEach{Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
  }
 }
 Text("SIGNAL HISTORY",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary); SignalHistory(d.history,d.tint)
 Text("DEVICE INFORMATION",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
 Detail("Detected category",d.category);Detail("Device address",d.address);Detail("First detected",date(d.first));Detail("Last detected",date(d.seen));Detail("Signal readings","${d.history.size} captured in this scan");Detail("Advertised transmit power",d.tx?.let{"$it dBm"}?:"Not provided");Detail("Advertised services",d.services.ifEmpty{listOf("No services listed")}.joinToString("\n"));Detail("Manufacturer information",d.manufacturer.ifBlank{"Not provided"});Detail("Service information",d.serviceData.ifBlank{"Not provided"});Detail("Broadcast bytes (first 64)",d.raw.ifBlank{"Not available"})
 Text("PAIRING SECURITY DETAILS",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
 Detail("Bonding support","Unknown until Android attempts pairing and the device responds.")
 Detail("Negotiated security level","Available only after the pairing exchange; this scan cannot determine it.")
 Text("A BLE scan cannot discover Classic-only devices. Their pairing format can only be shown after Classic discovery or connection. Transport detection does not guarantee that a device accepts pairing.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 Text("BLE does not measure exact distance. Signal strength is only a rough clue to proximity.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 OutlinedButton(onClick=close,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Close,null);Spacer(Modifier.width(7.dp));Text("Close details")}
}}}
@Composable private fun Detail(label:String,value:String){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)}}
private fun date(t:Long)=SimpleDateFormat("dd MMM yyyy, HH:mm:ss",Locale.getDefault()).format(Date(t))
private fun ago(t:Long):String{val s=((System.currentTimeMillis()-t).coerceAtLeast(0L))/1000;return when{s<2->"NOW";s<60->"${s}s AGO";else->"${s/60}m AGO"}}

@Composable private fun SignalHistory(values:List<Int>,tint:Color){
 Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
  val gridColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.12f)
  Canvas(Modifier.fillMaxWidth().height(76.dp)){
   val min=-100f;val max=-30f;val canvasWidth=this.size.width;val canvasHeight=this.size.height;val points=values.mapIndexed{index,value->androidx.compose.ui.geometry.Offset(if(values.size<=1)canvasWidth else index.toFloat()/(values.size-1)*canvasWidth,canvasHeight-((value.toFloat()-min)/(max-min)).coerceIn(0f,1f)*canvasHeight)}
   for(i in 1..3){val y=size.height*i/4;drawLine(gridColor,androidx.compose.ui.geometry.Offset(0f,y),androidx.compose.ui.geometry.Offset(size.width,y),1.dp.toPx())}
   if(points.size>1){val path=Path().apply{moveTo(points.first().x,points.first().y);points.drop(1).forEach{lineTo(it.x,it.y)}};drawPath(path,tint,style=Stroke(width=3.dp.toPx()))}
   points.forEach{drawCircle(tint,3.dp.toPx(),it)}
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("WEAK",fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("${values.size} readings",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("STRONG",fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
 }
}
