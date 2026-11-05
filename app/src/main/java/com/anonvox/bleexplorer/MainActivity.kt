package com.anonvox.bleexplorer
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

private data class BmsSnapshot(val status:String="Not connected",val batteryLevel:Int?=null,val characteristics:List<String> = emptyList(),val deviceAddress:String?=null,val connected:Boolean=false)
private data class Device(val address:String,val name:String,val category:String,val transport:String,val rssi:Int,val seen:Long,val first:Long,val tx:Int?,val services:List<String>,val manufacturer:String,val serviceData:String,val raw:String,val history:List<Int>,val bondState:Int=BluetoothDevice.BOND_NONE,val deviceClass:Int?=null,val majorDeviceClass:Int?=null,val alias:String="",val cachedUuids:List<String> = emptyList()) {
 val quality get()=when { rssi>=-55->"Very strong";rssi>=-68->"Strong";rssi>=-80->"Moderate";else->"Weak" }
 val tint get()=when { rssi>=-55->Color(0xFF22C55E);rssi>=-68->Color(0xFF84CC16);rssi>=-80->Color(0xFFF59E0B);else->Color(0xFFEF4444) }
}
class MainActivity:ComponentActivity() {
 private val handler=Handler(Looper.getMainLooper()); private var scanner:BluetoothLeScanner?=null;private var callback:ScanCallback?=null;private var active=false
 private val found=linkedMapOf<String,Device>();private val knownNames=mutableMapOf<String,String>();private var update:((List<Device>)->Unit)?=null;private var pairedUpdate:((List<Device>)->Unit)?=null;private var statusUpdate:((Boolean,String)->Unit)?=null
 private val timeout=Runnable{stop("Scan complete")};private var bmsGatt:BluetoothGatt?=null;@Volatile private var bmsDeviceAddress:String?=null;@Volatile private var bmsConnected=false;private var bmsUpdate:((BmsSnapshot)->Unit)?=null;private val gattReadQueue=java.util.ArrayDeque<BluetoothGattCharacteristic>();private val gattRows=mutableListOf<String>()
 private val classicReceiver=object:android.content.BroadcastReceiver(){
  override fun onReceive(context:Context?,intent:Intent?){
   when(intent?.action){
    BluetoothDevice.ACTION_FOUND->{
     val remote=try{intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)}catch(_:Exception){null}?:return
     try{
      val address=remote.address?:return
      val name=remote.name?.takeIf{it.isNotBlank()}?:knownNames[address]?:"Name unavailable"
      if(name!="Name unavailable")knownNames[address]=name
      val rssi=intent.getShortExtra(BluetoothDevice.EXTRA_RSSI,(-127).toShort()).toInt()
      val btClass=remote.bluetoothClass
      val now=System.currentTimeMillis()
      synchronized(found){
       val old=found[address]
       val transport=when(remote.type){BluetoothDevice.DEVICE_TYPE_CLASSIC->"Bluetooth Classic (BR/EDR)";BluetoothDevice.DEVICE_TYPE_DUAL->"Dual-mode (LE + Classic BR/EDR)";BluetoothDevice.DEVICE_TYPE_LE->"Bluetooth Low Energy (LE)";else->"Bluetooth transport reported by Android"}
       val category=classifyDevice(name,emptyList(),"","",btClass?.deviceClass,btClass?.majorDeviceClass)
       found[address]=Device(address,name,category,transport,rssi,now,old?.first?:now,null,emptyList(),"","","",((old?.history?:emptyList())+rssi).takeLast(20),remote.bondState,btClass?.deviceClass,btClass?.majorDeviceClass,if(Build.VERSION.SDK_INT>=30)remote.alias.orEmpty() else "",emptyList())
       val snapshot=found.values.sortedByDescending{it.rssi}
       runOnUiThread{update?.invoke(snapshot)}
      }
     }catch(_:SecurityException){}
    }
    BluetoothAdapter.ACTION_DISCOVERY_FINISHED->runOnUiThread{if(active)statusUpdate?.invoke(true,"Bluetooth Classic discovery complete · BLE scan continues")}
   }
  }
 }
 private val bondReceiver=object:android.content.BroadcastReceiver(){
  override fun onReceive(context:Context?,intent:Intent?){
   if(intent?.action!=BluetoothDevice.ACTION_BOND_STATE_CHANGED)return
   val remote=try{intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)}catch(_:Exception){null}?:return
   val state=intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.BOND_NONE)
   val address=try{remote.address}catch(_:SecurityException){return}
   synchronized(found){found[address]?.let{found[address]=it.copy(bondState=state)}}
   runOnUiThread{update?.invoke(found.values.sortedByDescending{it.rssi});loadPairedDevices()}
  }
 }
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{
  var devices by remember{mutableStateOf(emptyList<Device>())};var pairedDevices by remember{mutableStateOf(emptyList<Device>())};var scanning by remember{mutableStateOf(false)};var status by remember{mutableStateOf("Ready when you are")};var bms by remember{mutableStateOf(BmsSnapshot())}
  update={devices=it};pairedUpdate={pairedDevices=it};statusUpdate={a,s->scanning=a;status=s};bmsUpdate={bms=it}
  try{val bondFilter=IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);if(Build.VERSION.SDK_INT>=33)registerReceiver(bondReceiver,bondFilter,Context.RECEIVER_NOT_EXPORTED)else registerReceiver(bondReceiver,bondFilter)}catch(_:Exception){}
  try{val classicFilter=IntentFilter().apply{addAction(BluetoothDevice.ACTION_FOUND);addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)};if(Build.VERSION.SDK_INT>=33)registerReceiver(classicReceiver,classicFilter,Context.RECEIVER_NOT_EXPORTED)else registerReceiver(classicReceiver,classicFilter)}catch(_:Exception){}
  AppTheme { Screen(devices,pairedDevices,scanning,status,{requestScan()},{stop("Scan stopped")},{share(devices)},{device->pairDevice(device)},{device->connectBms(device)},{device->disconnectBms(device)},{startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))},bms,{startActivity(Intent(this,NetworkAuditActivity::class.java))},{startActivity(Intent(this,NearbyWifiActivity::class.java))},{startActivity(Intent(this,NearbyWifiDirectActivity::class.java))}) }
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
  scanner=adapter.bluetoothLeScanner;if(scanner==null){statusUpdate?.invoke(false,"Bluetooth scanner unavailable");return};loadPairedDevices()
  found.clear();update?.invoke(emptyList())
  callback=object:ScanCallback(){override fun onScanResult(type:Int,result:ScanResult){add(result)};override fun onBatchScanResults(results:MutableList<ScanResult>){results.forEach{add(it)}};override fun onScanFailed(code:Int){runOnUiThread{stop("Scan failed ($code). Try again.")}}}
  scanner?.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT).setReportDelay(0L).build(),callback)
  try{adapter.startDiscovery()}catch(_:SecurityException){}
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
   val retainedName=knownNames[addr]?:old?.name?.takeIf{it.isNotBlank()&&it!="Name unavailable"}
   val name=retainedName?:advertisedName?:cachedName?:"Name unavailable"
   if(name!="Name unavailable")knownNames[addr]=name
   val btClass=try{r.device.bluetoothClass}catch(_:Exception){null}
   val category=classifyDevice(name,services,m,sd,btClass?.deviceClass,btClass?.majorDeviceClass)
   val bond=try{r.device.bondState}catch(_:Exception){BluetoothDevice.BOND_NONE}
   val alias=try{if(Build.VERSION.SDK_INT>=30)r.device.alias.orEmpty() else ""}catch(_:Exception){""}
   val uuids=try{r.device.uuids?.map{it.uuid.toString()}.orEmpty()}catch(_:Exception){emptyList()}
   found[addr]=Device(addr,name,category,transport,r.rssi,now,old?.first?:now,rec?.txPowerLevel,services,m,sd,raw,((old?.history?:emptyList())+r.rssi).takeLast(20),bond,btClass?.deviceClass,btClass?.majorDeviceClass,alias,uuids)
   val list=found.values.sortedByDescending{it.rssi}
   runOnUiThread{update?.invoke(list)}
  }
 }
 private fun connectBms(device:Device){
  if(!allowed()){requestPermissions(requiredPermissions(),701);Toast.makeText(this,"Allow Nearby devices permission to connect",Toast.LENGTH_LONG).show();return}
  try{
   bmsGatt?.close();bmsGatt=null;bmsDeviceAddress=device.address;bmsConnected=false;bmsUpdate?.invoke(BmsSnapshot("Connecting to "+device.name+"…",deviceAddress=device.address))
   val remote=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.getRemoteDevice(device.address)
   if(remote==null){bmsUpdate?.invoke(BmsSnapshot("Device unavailable"));return}
   bmsGatt=remote.connectGatt(this,false,object:BluetoothGattCallback(){
    override fun onConnectionStateChange(gatt:BluetoothGatt,status:Int,newState:Int){
     if(status!=BluetoothGatt.GATT_SUCCESS){bmsConnected=false;runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Connection failed (GATT "+status+")",deviceAddress=bmsDeviceAddress))};gatt.close();return}
     if(newState==BluetoothProfile.STATE_CONNECTED){bmsConnected=true;runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Connected · discovering BMS services",deviceAddress=bmsDeviceAddress,connected=true))};gatt.discoverServices()}
     else if(newState==BluetoothProfile.STATE_DISCONNECTED){bmsConnected=false;runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Disconnected",deviceAddress=bmsDeviceAddress))};gatt.close();if(bmsGatt===gatt)bmsGatt=null}
    }
    override fun onServicesDiscovered(gatt:BluetoothGatt,status:Int){
     if(status!=BluetoothGatt.GATT_SUCCESS){runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Connected · service discovery failed",deviceAddress=bmsDeviceAddress,connected=true))};return}
     val rows=mutableListOf<String>();gattReadQueue.clear();var readableCount=0
     for(service in gatt.services){
      rows.add("SERVICE  "+service.uuid)
      for(ch in service.characteristics){
       val p=mutableListOf<String>()
       if(ch.properties and BluetoothGattCharacteristic.PROPERTY_READ!=0){p.add("Read");gattReadQueue.add(ch);readableCount++}
       if(ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE!=0)p.add("Write")
       if(ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE!=0)p.add("Write without response")
       if(ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY!=0)p.add("Notify")
       if(ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE!=0)p.add("Indicate")
       rows.add(ch.uuid.toString()+" · "+p.joinToString("/").ifBlank{"No standard access flags"})
       ch.descriptors.forEach{descriptor->rows.add("DESCRIPTOR  "+descriptor.uuid+" · belongs to "+ch.uuid)}
      }
     }
     gattRows.clear();gattRows.addAll(rows)
     runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Connected · "+gatt.services.size+" services · "+readableCount+" readable characteristics queued",characteristics=gattRows.toList(),deviceAddress=bmsDeviceAddress,connected=true))}
     readNextGattCharacteristic(gatt)
    }
    @Deprecated("Deprecated Android callback")
    override fun onCharacteristicRead(gatt:BluetoothGatt,characteristic:BluetoothGattCharacteristic,status:Int){
     recordGattCharacteristicRead(gatt,characteristic,characteristic.value?:byteArrayOf(),status)
    }
    override fun onCharacteristicRead(gatt:BluetoothGatt,characteristic:BluetoothGattCharacteristic,value:ByteArray,status:Int){
     recordGattCharacteristicRead(gatt,characteristic,value,status)
    }
   })
  }catch(_:SecurityException){bmsUpdate?.invoke(BmsSnapshot("Bluetooth permission required"))}catch(_:Exception){bmsUpdate?.invoke(BmsSnapshot("Could not connect to this device"))}
 }
 private fun readNextGattCharacteristic(gatt:BluetoothGatt){
  if(gattReadQueue.isEmpty()){
   runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Connected · GATT inspection complete",characteristics=gattRows.toList(),deviceAddress=bmsDeviceAddress,connected=bmsConnected))}
   return
  }
  val next=gattReadQueue.removeFirst()
  try{
   if(!gatt.readCharacteristic(next))handler.postDelayed({readNextGattCharacteristic(gatt)},180)
  }catch(_:SecurityException){gattRows.add("READ BLOCKED  "+next.uuid+" · Android permission or device authorization required");handler.postDelayed({readNextGattCharacteristic(gatt)},180)}
  catch(_:Exception){gattRows.add("READ FAILED  "+next.uuid);handler.postDelayed({readNextGattCharacteristic(gatt)},180)}
 }
 private fun recordGattCharacteristicRead(gatt:BluetoothGatt,characteristic:BluetoothGattCharacteristic,value:ByteArray,status:Int){
  var level:Int?=null
  if(status==BluetoothGatt.GATT_SUCCESS){
   val uuid=characteristic.uuid.toString()
   if(uuid.equals("00002a19-0000-1000-8000-00805f9b34fb",true))level=value.firstOrNull()?.toInt()?.and(0xFF)
   val decoded=decodeGattValue(value)
   gattRows.add("READ VALUE  "+uuid+" = "+decoded)
  }else gattRows.add("READ FAILED  "+characteristic.uuid+" · GATT status "+status)
  runOnUiThread{bmsUpdate?.invoke(BmsSnapshot("Reading device characteristics · "+gattReadQueue.size+" remaining",level,gattRows.toList(),bmsDeviceAddress,bmsConnected))}
  readNextGattCharacteristic(gatt)
 }
 private fun decodeGattValue(value:ByteArray):String{
  if(value.isEmpty())return "Empty value"
  val hex=value.take(64).joinToString(" "){String.format(Locale.US,"%02X",it.toInt() and 0xFF)}
  val text=String(value,Charsets.UTF_8).trim().trim('\u0000')
  return if(text.isNotBlank()&&text.all{it.isLetterOrDigit()||it.isWhitespace()||it in "._:/-+()"})"Text: "+text+" · HEX: "+hex else "HEX: "+hex
 }
 private fun loadPairedDevices(){
  if(!allowed())return
  try{
   val adapter=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?:return
   val snapshots=adapter.bondedDevices.mapNotNull{remote->
    try{
     val address=remote.address?:return@mapNotNull null
     val alias=try{if(Build.VERSION.SDK_INT>=30)remote.alias.orEmpty() else ""}catch(_:Exception){""}
     val name=alias.takeIf{it.isNotBlank()}?:remote.name?.takeIf{it.isNotBlank()}?:"Name unavailable"
     val btClass=remote.bluetoothClass
     val transport=when(remote.type){BluetoothDevice.DEVICE_TYPE_LE->"Bluetooth Low Energy (LE)";BluetoothDevice.DEVICE_TYPE_CLASSIC->"Bluetooth Classic (BR/EDR)";BluetoothDevice.DEVICE_TYPE_DUAL->"Dual-mode (LE + Classic BR/EDR)";else->"Unknown Bluetooth transport"}
     Device(address,name,classifyDevice(name,emptyList(),"","",btClass?.deviceClass,btClass?.majorDeviceClass),transport,-127,0L,0L,null,emptyList(),"","","",emptyList(),remote.bondState,btClass?.deviceClass,btClass?.majorDeviceClass,alias,remote.uuids?.map{it.uuid.toString()}.orEmpty())
    }catch(_:SecurityException){null}catch(_:Exception){null}
   }.sortedBy{it.name.lowercase(Locale.ROOT)}
   pairedUpdate?.invoke(snapshots)
  }catch(_:SecurityException){pairedUpdate?.invoke(emptyList())}
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
 private fun disconnectBms(device:Device){if(bmsDeviceAddress!=device.address||!bmsConnected){Toast.makeText(this,"No app-managed BLE connection is active for this device",Toast.LENGTH_SHORT).show();return};try{bmsConnected=false;bmsUpdate?.invoke(BmsSnapshot("Disconnecting from "+device.name+"…",deviceAddress=device.address));bmsGatt?.disconnect()}catch(_:SecurityException){Toast.makeText(this,"Bluetooth permission is required to disconnect",Toast.LENGTH_LONG).show()}catch(_:Exception){Toast.makeText(this,"Could not disconnect this device",Toast.LENGTH_LONG).show()}}
 private fun stop(message:String){handler.removeCallbacks(timeout);if(active){try{scanner?.stopScan(callback)}catch(_:SecurityException){};try{(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.cancelDiscovery()}catch(_:SecurityException){};active=false};statusUpdate?.invoke(false,message)}
 private fun share(ds:List<Device>){
  val captured=ds.map{d->mapOf("name" to d.name,"category_estimate" to d.category,"address" to d.address,"transport_reported_by_android" to d.transport,"rssi_dbm" to d.rssi,"signal_quality_estimate" to d.quality,"first_seen" to if(d.first>0)date(d.first) else null,"last_seen" to if(d.seen>0)date(d.seen) else null,"advertised_tx_power_dbm" to d.tx,"advertised_service_uuids" to d.services,"manufacturer_data_hex" to d.manufacturer,"service_data_hex" to d.serviceData,"raw_advertisement_hex" to d.raw,"rssi_history_dbm" to d.history,"bond_state" to when(d.bondState){BluetoothDevice.BOND_BONDED->"paired";BluetoothDevice.BOND_BONDING->"pairing";else->"not paired"},"identity_note" to "Category is an estimate from observed names/classes/UUIDs; exact model and firmware are not verified.")}
  val json=org.json.JSONArray().apply{captured.forEach{put(org.json.JSONObject(it))}}.toString(2)
  val text=buildString{
   appendLine("BLE EXPLORER — DEVICE DISCOVERY REPORT")
   appendLine("Generated: "+date(System.currentTimeMillis()))
   appendLine("Devices captured: "+ds.size)
   appendLine("Scope: passive BLE advertisements and Android-reported paired-device metadata.")
   appendLine("Safety: identifiers and category labels are evidence-based estimates, not proof of identity or vulnerability.")
   ds.forEachIndexed{index,d->
    appendLine();appendLine("DEVICE ${index+1}: ${d.name}")
    appendLine("Category estimate: ${d.category}");appendLine("Address: ${d.address}");appendLine("Transport: ${d.transport}")
    appendLine("Signal: ${d.rssi} dBm (${d.quality})");appendLine("First seen: "+if(d.first>0)date(d.first) else "Unavailable")
    appendLine("Last seen: "+if(d.seen>0)date(d.seen) else "Unavailable")
    appendLine("Advertised services: "+d.services.ifEmpty{listOf("None captured")}.joinToString())
    appendLine("Manufacturer data: "+d.manufacturer.ifBlank{"Not advertised"})
    appendLine("Service data: "+d.serviceData.ifBlank{"Not advertised"})
    appendLine("Raw advertisement: "+d.raw.ifBlank{"Not captured"})
    appendLine("RSSI history: "+d.history.joinToString())
    appendLine("Identity confidence: "+if(d.services.isNotEmpty()||d.manufacturer.isNotBlank())"Observed identifiers available; exact model unverified" else "Name/class heuristic only")
   }
  }
  ReportExporter.shareAll(this,"BLE Explorer device discovery report","ble-explorer-devices",text,json)
 }
 override fun onDestroy(){stop("App closed");try{unregisterReceiver(bondReceiver)}catch(_:Exception){};try{unregisterReceiver(classicReceiver)}catch(_:Exception){};try{bmsGatt?.disconnect();bmsGatt?.close()}catch(_:Exception){};bmsGatt=null;update=null;pairedUpdate=null;statusUpdate=null;bmsUpdate=null;super.onDestroy()}
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

private fun classifyDevice(name:String,services:List<String>,manufacturer:String,serviceData:String,deviceClass:Int?=null,majorDeviceClass:Int?=null):String {
 val n=(name+" "+manufacturer+" "+serviceData).lowercase(Locale.ROOT)
 val minorClass=deviceClass?.and(0x00FC)?:-1
 val u=services.map{raw->val compact=raw.replace("-","").lowercase(Locale.ROOT);if(compact.length==32)compact.substring(4,8) else compact}
 fun has(vararg ids:String)=u.any{v->ids.any{v==it}}
 fun named(vararg words:String)=words.any{n.contains(it)}
 return when {
  named("ev battery","ev-battery","traction battery","battery management system","battery pack","bms","lifepo","li-fe-po","lithium battery","e-scooter battery","electric scooter battery","electric vehicle battery","e-bike battery","ebike battery","inverter battery","bms_","bms-","jbd","dal y bms","jk-bms","seplos","ant bms","pace bms","scooter bms") -> "EV Batteries"
  named("cctv","ip camera","security camera","surveillance","network camera","camera_","camera-","cam_","cam-","nvr","dvr","reolink","arlo","wyze","eufy","ring camera","hikvision","dahua","ezviz","imou","uniview","axis camera","amcrest","tapocam","tapo c","v380","yoosee","xmeye") -> "CCTV Cameras"
  named("electric vehicle","ev car","ev scooter","ev bike","electric car","electric scooter","e-scooter","electric bike","e-bike","ebike","electric motorcycle","tesla","ather","ola electric","bajaj chetak","tvs iqube","revolt","river indie") -> "EV Vehicles"
  named("smart tv","android tv","google tv","fire tv","roku tv","webos tv","tizen tv","television","samsung tv","lg tv","sony bravia","bravia tv","mi tv","oneplus tv","tv-","tv_") || (majorDeviceClass==0x0400&&minorClass in setOf(0x24,0x38,0x3C)) -> "TVs"
  named("tv remote","remote control","remote_","remote-","remote","magic remote","smart remote") || deviceClass==0x050C -> "Remotes"
  named("airpods","earbuds","ear bud","headphone","headset","neckband","soundbar","speaker","galaxy buds","pixel buds","wf-","wh-1000","boAt","boat ","jbl","bose","sony audio","beats ") || has("110b","110d","110e","110f","111e","111f","1124","1130","1131") -> "Audio"
  named("iphone","ipad","android phone","samsung galaxy","galaxy s","galaxy note","pixel phone","oneplus","xiaomi","redmi","realme","vivo ","oppo ","motorola","nothing phone","huawei phone","honor phone") -> "Phones"
  named("apple watch","galaxy watch","pixel watch","fitbit","garmin","smartwatch","smart watch","fitness band","mi band","miband","fitness tracker","heart rate","wearable","watch ") || has("180d","1816","1818","1819","1822","183a") -> "Wearables"
  named("airtag","tile","smarttag","tracker","find my","ibeacon","eddystone","beacon","chipolo","pebblebee") || has("feaa","feab") -> "Trackers"
  named("obd","tpms","tire pressure","tyre pressure","vehicle","motorcycle","car diagnostic","elm327","vlinker","scooter","ebike","e-bike") -> "Automotive"
  named("smart bulb","smart light","smart plug","smart switch","thermostat","homekit","smart home","zigbee","matter","hue","lifx","tuya","sonoff","aqara") || has("fd6f","fcf1") -> "Smart Home"
  named("gamepad","game controller","joystick","xbox controller","playstation controller","dualshock","dualsense","nintendo switch pro") -> "Game Controllers"
  named("keyboard","mouse","trackpad","hid") || has("1812") -> "Input Devices"
  named("laptop","notebook","desktop","macbook","thinkpad","surface pro","chromebook","windows pc") -> "Computers"
  named("printer","label maker","thermal printer","barcode scanner") -> "Printers"
  named("glucose","glucometer","pulse oximeter","blood pressure","medical","hearing aid","insulin") || has("1808","1810","181a","1821") -> "Medical"
  named("battery","batt ","batt_","batt-","power bank","ups battery","lithium pack","lifepo4","li-ion","li ion","bms") -> "Batteries"
  else -> "Unknown"
 }
}
private fun bluetoothClassName(code:Int):String=when(code and 0x1F00){
 0x0100->"Computer";0x0200->"Phone";0x0300->"Networking";0x0400->"Audio / video";0x0500->"Peripheral";0x0600->"Imaging";0x0700->"Wearable";0x0800->"Toy";0x0900->"Health";else->"Other / vendor-defined device class"
}
private fun majorBluetoothClassName(code:Int):String=when(code){
 0x0100->"Computer";0x0200->"Phone";0x0300->"Network access point";0x0400->"Audio / video";0x0500->"Peripheral";0x0600->"Imaging";0x0700->"Wearable";0x0800->"Toy";0x0900->"Health";0x1F00->"Uncategorized";else->"Other / unknown"
}
private fun gattRowTitle(row:String):String=when{
 row.startsWith("SERVICE")->"GATT service"
 row.contains("00002a19",true)->"Battery level characteristic"
 row.contains("00002a29",true)->"Manufacturer name characteristic"
 row.contains("00002a24",true)->"Model number characteristic"
 row.contains("00002a26",true)->"Firmware revision characteristic"
 row.contains("00002a27",true)->"Hardware revision characteristic"
 row.contains("00002a25",true)->"Serial number characteristic"
 row.contains("00002a23",true)->"System ID characteristic"
 row.contains("00002a50",true)->"PnP ID characteristic"
 else->"GATT characteristic / permissions"
}
private fun gattRowDescription(row:String):String=when{
 row.startsWith("SERVICE")->row.removePrefix("SERVICE  ")+" — a group of related functions exposed by the device"
 row.contains("00002a19",true)->"${row} — battery charge remaining, normally expressed as a percentage"
 row.contains("00002a29",true)->"${row} — manufacturer name, if the device makes it readable"
 row.contains("00002a24",true)->"${row} — product model identifier, if readable"
 row.contains("00002a26",true)->"${row} — firmware version string, if readable"
 row.contains("00002a27",true)->"${row} — hardware revision string, if readable"
 row.contains("00002a25",true)->"${row} — serial number; may be restricted or unavailable"
 row.contains("00002a23",true)->"${row} — Bluetooth SIG system identifier"
 row.contains("00002a50",true)->"${row} — vendor/product identifiers and product version"
 else->"${row} — access flags show whether the characteristic advertises read, write, notify or indicate operations"
}
@Composable private fun AppTheme(content: @Composable () -> Unit){val dark=isSystemInDarkTheme();MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Color(0xFFFBDA95),secondary=Color(0xFFDAA96A),tertiary=Color(0xFF94748D),background=Color(0xFF231610),surface=Color(0xFF35241F),surfaceVariant=Color(0xFF49332B),onSurface=Color(0xFFF1E7E2),onSurfaceVariant=Color(0xFFD2BDB1),outline=Color(0xFF806457))else lightColorScheme(primary=Color(0xFF9D320C),secondary=Color(0xFFB8521D),tertiary=Color(0xFF94748D),background=Color(0xFFF1E7E2),surface=Color(0xFFFFFAF6),surfaceVariant=Color(0xFFEAD8CE),onSurface=Color(0xFF5A2D1D),onSurfaceVariant=Color(0xFF806457),outline=Color(0xFFD6BDAE)),content=content)}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Screen(devices:List<Device>,pairedDevices:List<Device>,active:Boolean,status:String,onScan:()->Unit,onStop:()->Unit,onShare:()->Unit,onPair:(Device)->Unit,onConnectBms:(Device)->Unit,onDisconnectBms:(Device)->Unit,onOpenBluetoothSettings:()->Unit,bms:BmsSnapshot,onNetworkAudit:()->Unit,onNearbyWifi:()->Unit,onNearbyWifiDirect:()->Unit){
 var compact by remember{mutableStateOf(false)}
 var query by remember{mutableStateOf("")}
 var filter by remember{mutableStateOf("All")}
 var selected by remember{mutableStateOf<Device?>(null)}
 var settings by remember{mutableStateOf(false)}
 var reportChoices by remember{mutableStateOf(false)}
 val categories=listOf("All","Audio","Phones","Wearables","CCTV Cameras","TVs","Remotes","EV Vehicles","Smart Home","Unknown")
 LaunchedEffect(devices,pairedDevices){selected=selected?.let{chosen->(devices+pairedDevices).firstOrNull{it.address==chosen.address}?:chosen}}
 val shown=devices.filter{(it.name.contains(query,true)||it.address.contains(query,true))&&(filter=="All"||it.category==filter)}
 val strongest=devices.maxByOrNull{it.rssi}
 Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={TopAppBar(title={
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){
   Box(Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF9D320C),Color(0xFFDAA96A)))),contentAlignment=Alignment.Center){Icon(Icons.Default.BluetoothSearching,null,tint=Color.White,modifier=Modifier.size(24.dp))}
   Column{Text("BLE Explorer",fontWeight=FontWeight.Bold,fontSize=20.sp);Text("Discover what’s around you",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
  }
 },actions={IconButton(onClick={settings=true}){Icon(Icons.Default.Settings,"Settings")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))}){pad->
 LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(start=18.dp,end=18.dp,top=8.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
  item{
   Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(Color(0xFFFFEBCB),Color(0xFFE9B17A),Color(0xFFB85A2B)))).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(if(active)"SCANNING NEARBY" else "YOUR DEVICE RADAR",style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,color=Color(0xFF6D321D),letterSpacing=1.4.sp);Text(if(active)"Listening for signals" else "Find nearby devices",fontSize=25.sp,lineHeight=29.sp,fontWeight=FontWeight.Bold,color=Color(0xFF472319));Text(if(active)"Live Bluetooth advertisements" else "Discover first. Connect only when you choose.",style=MaterialTheme.typography.bodySmall,color=Color(0xFF6D432F))};Icon(Icons.Default.Radar,null,tint=Color(0xFF6D321D),modifier=Modifier.size(35.dp))}
    Box(Modifier.fillMaxWidth().height(172.dp),contentAlignment=Alignment.Center){
     Canvas(Modifier.fillMaxSize()){val c=androidx.compose.ui.geometry.Offset(size.width/2,size.height/2);val r=size.minDimension*.43f;listOf(.45f,.72f,1f).forEach{drawCircle(Color(0x557A3B23),r*it,c,style=Stroke(width=1.2.dp.toPx()))};drawCircle(Color(0x337A3B23),r*.12f,c)}
     Box(Modifier.size(112.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFFFF8E8),Color(0xFFD97B3C),Color(0xFF9D320C)))),contentAlignment=Alignment.Center){Icon(if(active)Icons.Default.Radar else Icons.Default.BluetoothSearching,null,tint=Color.White,modifier=Modifier.size(48.dp))}
     Box(Modifier.align(Alignment.TopCenter).offset(x=(-104).dp,y=20.dp).size(12.dp).clip(CircleShape).background(Color(0xFFFFF8E8)))
     Box(Modifier.align(Alignment.BottomEnd).offset(x=(-38).dp,y=(-14).dp).size(9.dp).clip(CircleShape).background(Color(0xFF8B3E20)))
     Box(Modifier.align(Alignment.CenterEnd).offset(x=(-16).dp,y=(-54).dp).size(7.dp).clip(CircleShape).background(Color(0xFFFFF8E8)))
    }
    Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){
     Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0x33FFFFFF)).padding(12.dp)){Text("FOUND",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Color(0xFF70412B));Text(devices.size.toString(),fontSize=23.sp,fontWeight=FontWeight.Bold,color=Color(0xFF472319))}
     Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0x33FFFFFF)).padding(12.dp)){Text("PAIRED",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Color(0xFF70412B));Text(pairedDevices.size.toString(),fontSize=23.sp,fontWeight=FontWeight.Bold,color=Color(0xFF472319))}
     Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0x33FFFFFF)).padding(12.dp)){Text("STRONGEST",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Color(0xFF70412B));Text(strongest?.let{"${it.rssi} dBm"}?:"—",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Color(0xFF472319))}
    }
    Button(onClick=if(active)onStop else onScan,modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(18.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF49261D),contentColor=Color.White)){Icon(if(active)Icons.Default.Stop else Icons.Default.Radar,null);Spacer(Modifier.width(9.dp));Text(if(active)"Stop discovery" else if(devices.isEmpty())"Start discovery" else "Scan again",fontWeight=FontWeight.Bold,fontSize=16.sp)}
    Text(status,style=MaterialTheme.typography.labelMedium,color=Color(0xFF70412B))
   }
  }
  item{
   Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
    Text("Device intelligence tools",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    Text("Five connected capabilities. Start a scan, inspect evidence, audit your authorized network and export the findings.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    FeatureNote("P1 · Deep Device Inspector","Tap any discovered device card to inspect radio details, signal history, raw advertisement evidence and supported BLE GATT services.")
    ToolTile("P2 · Multi-protocol Discovery","BLE + Bluetooth Classic scan together; inspect nearby Wi-Fi access points and local IP services",Icons.Default.Hub,{onScan()})
    FeatureNote("P3 · Evidence-based Identification","Classification uses observed names, Bluetooth classes, service UUIDs and manufacturer payloads. Estimates and unavailable information are labelled.")
    ToolTile("P4 · Network & CCTV Intelligence","Run authorized subnet checks for ONVIF, SSDP/UPnP, RTSP and reachable management ports",Icons.Default.Security,onNetworkAudit)
    ToolTile("P5 · Evidence Reports","Choose Bluetooth, Wi-Fi or network findings to export as TXT, JSON and PDF",Icons.Default.Description,{reportChoices=true})
    ToolTile("Nearby Wi-Fi access points","Scan broadcast access points; this does not enumerate clients behind a router",Icons.Default.Wifi,onNearbyWifi)
   }
  }
  item{
   Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
    Text("Explore by type",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    Text("Choose a category to filter devices actually discovered in this scan.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(13.dp)){
     categories.forEachIndexed{index,c->
      val icon=when(c){"Audio"->Icons.Default.Headphones;"Phones"->Icons.Default.Smartphone;"Wearables"->Icons.Default.Watch;"CCTV Cameras"->Icons.Default.CameraAlt;"TVs"->Icons.Default.Tv;"Remotes"->Icons.Default.SettingsRemote;"EV Vehicles"->Icons.Default.DirectionsCar;"Smart Home"->Icons.Default.Lightbulb;"Unknown"->Icons.Default.DevicesOther;else->Icons.Default.Apps}
      Column(Modifier.width(70.dp).clickable{filter=c}.padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)){
       Box(Modifier.size(if(filter==c)62.dp else 54.dp).clip(CircleShape).background(if(filter==c)Color(0xFFB8521D) else MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center){Icon(icon,null,tint=if(filter==c)Color.White else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(25.dp))}
       Text(c,fontSize=10.sp,fontWeight=if(filter==c)FontWeight.Bold else FontWeight.Medium,maxLines=2,minLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=MaterialTheme.colorScheme.onSurface)
      }
     }
    }
   }
  }
  item{Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Nearby discoveries",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("${shown.size} matching devices",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};FilledTonalIconButton(onClick={compact=!compact}){Icon(if(compact)Icons.Default.GridView else Icons.Default.ViewAgenda,"Change layout")};FilledTonalIconButton(onClick=onShare,enabled=devices.isNotEmpty()){Icon(Icons.Default.Share,"Share scan data")}}
   OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("Search by name or address")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true,shape=RoundedCornerShape(18.dp))
  }}
  if(pairedDevices.isNotEmpty()){
   item{Text("PAIRED WITH THIS PHONE",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}
   items(pairedDevices,key={"paired-"+it.address}){d->PairedDeviceCard(d){selected=d}}
  }
  if(shown.isEmpty())item{
   Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(9.dp)){
    Icon(Icons.Default.BluetoothSearching,null,modifier=Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary)
    Text(if(devices.isEmpty())"Nothing discovered yet" else "No devices in this category",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
    Text(if(devices.isEmpty())"Start a scan to show live Bluetooth devices. No sample devices are displayed." else "Try another category or clear the search.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
   }
  } else items(shown,key={it.address}){d->DeviceCard(d,compact){selected=d}}
  item{Text("Only observed values are shown. Device type is an estimate based on broadcast evidence; missing details remain unavailable until the device exposes them.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
 }}
 selected?.let{device->DeviceSheet(device,{onPair(device)},{onConnectBms(device)},{onDisconnectBms(device)},{onOpenBluetoothSettings()},bms){selected=null}}
 if(settings)AlertDialog(onDismissRequest={settings=false},title={Text("Discovery settings")},text={Text("Bluetooth discovery requires Nearby devices permission. Android versions and device manufacturers may impose additional restrictions. Scans start only when requested and stop automatically after 30 seconds.")},confirmButton={TextButton(onClick={settings=false}){Text("Done")}})
 if(reportChoices)AlertDialog(onDismissRequest={reportChoices=false},title={Text("Export evidence report")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("Choose the evidence source. Each tool exports its own captured data in TXT, JSON and PDF formats.")
  TextButton(onClick={reportChoices=false;if(devices.isNotEmpty())onShare() else onScan()},modifier=Modifier.fillMaxWidth()){Text(if(devices.isNotEmpty())"Bluetooth discovery report" else "Start Bluetooth discovery first")}
  TextButton(onClick={reportChoices=false;onNearbyWifi()},modifier=Modifier.fillMaxWidth()){Text("Nearby Wi-Fi access-point report")}
  TextButton(onClick={reportChoices=false;onNetworkAudit()},modifier=Modifier.fillMaxWidth()){Text("Local network & CCTV audit report")}
 },confirmButton={TextButton(onClick={reportChoices=false}){Text("Close")}}})
}
@Composable private fun FeatureNote(title:String,subtitle:String){
 Surface(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(19.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp){Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
}
@Composable private fun ToolTile(title:String,subtitle:String,icon:ImageVector,onClick:()->Unit){
 Surface(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(19.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp){
  Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)){
   Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),contentAlignment=Alignment.Center){Icon(icon,null,tint=MaterialTheme.colorScheme.onSecondaryContainer)}
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
   Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}
@Composable private fun Metric(label:String,value:String,icon:ImageVector,modifier:Modifier=Modifier){Column(modifier.clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha=.72f)).padding(horizontal=9.dp,vertical=11.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(17.dp));Text(value,fontWeight=FontWeight.Bold,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(label,fontSize=9.sp,letterSpacing=1.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=FontWeight.Bold)}}
@Composable private fun PairedDeviceCard(d:Device,click:()->Unit){
 Surface(Modifier.fillMaxWidth().clickable(onClick=click),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.18f))){
  Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){
   Glyph(d,42)
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
    Text(d.name,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
    Text(d.address,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text(d.transport,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
   Tag("PAIRED")
   Icon(Icons.Default.ChevronRight,null)
  }
 }
}
@Composable private fun DeviceCard(d:Device,compact:Boolean,click:()->Unit){Surface(Modifier.fillMaxWidth().clickable(onClick=click),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=3.dp,shadowElevation=if(isSystemInDarkTheme())2.dp else 7.dp,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=if(isSystemInDarkTheme()).28f else .18f))){
 if(compact)Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){Glyph(d,44);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(d.name,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(d.address,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)};Column(horizontalAlignment=Alignment.End){Text("${d.rssi} dBm",fontWeight=FontWeight.Bold,color=d.tint,fontSize=13.sp);Text(d.quality,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.ChevronRight,null)}
 else Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Glyph(d,52);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(d.name,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(d.name=="Name unavailable")"Name not shared by this device" else d.transport,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)}
 Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(d.quality,fontWeight=FontWeight.SemiBold,color=d.tint);Text("Signal strength",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("${d.rssi} dBm",fontWeight=FontWeight.Bold,fontSize=18.sp,color=d.tint)}
 Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)){Box(Modifier.fillMaxWidth(((d.rssi+100).coerceIn(0,70)/70f)).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(d.tint.copy(alpha=.65f),d.tint))))}
 Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){Tag(d.category.uppercase(Locale.ROOT));Tag("SEEN ${ago(d.seen)}");if(d.services.isNotEmpty())Tag("${d.services.size} SERVICES");d.tx?.let{Tag("TX $it dBm")}}
 }}
}
@Composable private fun Glyph(d:Device,size:Int){val palettes=listOf(listOf(Color(0xFFB8521D),Color(0xFFDAA96A)),listOf(Color(0xFF9D320C),Color(0xFF5A2D1D)),listOf(Color(0xFF94748D),Color(0xFFDAA96A)));val glyph=when(d.category){ "TVs"->Icons.Default.Tv; "Remotes"->Icons.Default.SettingsRemote; "EV Vehicles","Automotive"->Icons.Default.DirectionsCar; "CCTV Cameras"->Icons.Default.CameraAlt; "Audio"->Icons.Default.Headphones; "Phones"->Icons.Default.Smartphone; "Wearables"->Icons.Default.Watch; "Trackers"->Icons.Default.LocationOn; "Smart Home"->Icons.Default.Lightbulb; "Input Devices"->Icons.Default.Keyboard; "Computers"->Icons.Default.Laptop; "Printers"->Icons.Default.Print; "Medical"->Icons.Default.Favorite; "EV Batteries","Batteries"->Icons.Default.BatteryChargingFull; "Networking"->Icons.Default.Router; "Game Controllers"->Icons.Default.SportsEsports; else->Icons.Default.DevicesOther };Box(Modifier.size(size.dp).clip(RoundedCornerShape(17.dp)).background(Brush.linearGradient(palettes[kotlin.math.abs(d.name.hashCode()%palettes.size)])),contentAlignment=Alignment.Center){Icon(glyph,null,tint=Color.White,modifier=Modifier.size((size*.56).dp))}}
@Composable private fun Tag(s:String){Text(s,Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=8.dp,vertical=5.dp),fontSize=9.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DeviceSheet(d:Device,onPair:()->Unit,onConnectBms:()->Unit,onDisconnectBms:()->Unit,onOpenBluetoothSettings:()->Unit,bms:BmsSnapshot,close:()->Unit){val deviceBms=if(bms.deviceAddress==d.address)bms else BmsSnapshot();ModalBottomSheet(onDismissRequest=close,containerColor=MaterialTheme.colorScheme.surface){Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Glyph(d,58);Column(Modifier.weight(1f)){Text(d.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("${if(d.category=="Unknown")"Unclassified device" else d.category} · Detected Bluetooth broadcast",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}}
 when { deviceBms.connected&&deviceBms.deviceAddress==d.address -> Button(onClick=onDisconnectBms,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(15.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.BluetoothDisabled,null);Spacer(Modifier.width(8.dp));Text("Disconnect",fontWeight=FontWeight.Bold)}; d.bondState==BluetoothDevice.BOND_BONDED -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton(onClick={},enabled=false,modifier=Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(15.dp)){Icon(Icons.Default.CheckCircle,null);Spacer(Modifier.width(8.dp));Text("Paired",fontWeight=FontWeight.Bold)};TextButton(onClick=onOpenBluetoothSettings,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("Manage connection in Bluetooth settings")}}; d.bondState==BluetoothDevice.BOND_BONDING -> OutlinedButton(onClick={},enabled=false,modifier=Modifier.fillMaxWidth().height(50.dp)){Text("Pairing in progress…")}; else -> Button(onClick=onPair,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(15.dp)){Icon(Icons.Default.Lock,null);Spacer(Modifier.width(8.dp));Text("Pair securely",fontWeight=FontWeight.Bold)} }
 Text("PAIRING FORMAT & COMPATIBILITY",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
 Detail("Detected Bluetooth transport",d.transport)
 Detail("Pairing protocol","BLE devices use SMP when pairing is supported. Classic Bluetooth uses BR/EDR pairing.")
 Detail("Possible pairing methods","Just Works, Passkey Entry, Numeric Comparison or Out-of-Band (OOB) — selected during pairing based on both devices.")
 Detail("LE Secure Connections","Not confirmed by scan. If both devices support it, BLE pairing can use ECDH P-256.")
 Detail("Pairing availability","Not guaranteed by discovery. Some devices only broadcast and do not accept pairing.")
 if(d.rssi>-127)Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("SIGNAL","${d.rssi} dBm",Icons.Default.SignalCellularAlt,Modifier.weight(1f));Metric("QUALITY",d.quality,Icons.Default.CheckCircle,Modifier.weight(1f))}else Text("Signal strength will appear after this device is observed in a live scan.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 Text("GATT DEVICE INSPECTION",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
 Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)).padding(13.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
  Detail("Connection and discovery status",deviceBms.status+" — GATT is the Bluetooth Low Energy data interface used by many sensors and smart devices")
  if(d.transport.contains("Low Energy")||d.transport.contains("Dual-mode"))Button(onClick=onConnectBms,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.BluetoothConnected,null);Spacer(Modifier.width(7.dp));Text("Connect & inspect device services")}else Text("GATT inspection is not available for a Classic-only or unidentified transport. Classic Bluetooth uses different profiles (such as audio, HID or serial) and does not expose BLE GATT services.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(deviceBms.characteristics.isNotEmpty()){
   Text("Discovered GATT services and characteristics",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelSmall)
   deviceBms.characteristics.forEach{row->Detail(gattRowTitle(row),gattRowDescription(row))}
  }else Text("No GATT service data loaded yet. Connect to a BLE device to ask it for its public service and characteristic list. Some devices require authentication or do not allow connections.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }
 if(d.category=="EV Batteries"||d.category=="Batteries"){
  Text("EV BATTERY · BMS MONITOR",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)).padding(13.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Text(deviceBms.status,style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Metric("BATTERY LEVEL",deviceBms.batteryLevel?.let{it.toString()+"%"}?:"—",Icons.Default.BatteryFull,Modifier.weight(1f))
    Metric("GATT SERVICES",deviceBms.characteristics.count{it.startsWith("SERVICE")}.toString(),Icons.Default.SettingsBluetooth,Modifier.weight(1f))
   }
   Text("LIVE TELEMETRY",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
   listOf("Pack voltage","Charge / discharge current","State of charge (SOC)","State of health (SOH)","Remaining / full capacity","Cell voltage min / max","Cell voltage difference","Cell-by-cell voltages","Battery temperature sensors","Charge / discharge MOSFET","Cycle count","Protection & fault alarms").forEach{label->Detail(label,"Not exposed by this device's currently decoded GATT data")}
   Text("The standard Bluetooth Battery Service can provide a percentage only. Pack and cell telemetry requires a supported BMS vendor protocol or documented GATT characteristics. Raw service and characteristic UUIDs below help identify compatible hardware.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(deviceBms.characteristics.isNotEmpty()){Text("DISCOVERED GATT CHARACTERISTICS",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelSmall);deviceBms.characteristics.take(60).forEach{Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
  }
 }
 Text("SIGNAL HISTORY",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary);if(d.history.isNotEmpty())SignalHistory(d.history,d.tint)else Text("No signal samples recorded for this paired-only device.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 Text("DEVICE EVIDENCE & IDENTIFICATION",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
 Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)).padding(13.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
  Text("Evidence summary",fontWeight=FontWeight.SemiBold)
  Detail("Identity confidence",when{d.category=="Unknown"->"Unclassified · no reliable category evidence";d.services.isNotEmpty()||d.manufacturer.isNotBlank()||d.serviceData.isNotBlank()->"Evidence-backed category estimate · inspect the raw identifiers below";else->"Name/class heuristic only · product model is not verified"})
  Detail("Name evidence",if(d.name=="Name unavailable")"No name was included in the observed advertisement or available Android cache" else "Name reported by the Bluetooth advertisement or Android device cache · not a verified manufacturer/model")
  Detail("Transport evidence",d.transport+" · reported by Android Bluetooth APIs")
  Detail("Advertisement evidence",if(d.raw.isBlank())"No advertisement payload captured" else "Raw advertisement payload captured · ${d.raw.split(" ").size} bytes shown (maximum 64)")
  Detail("Manufacturer evidence",if(d.manufacturer.isBlank())"No manufacturer-specific data advertised" else "Manufacturer-specific data received · company identity is not decoded unless the payload identifies it")
  Detail("Service evidence",if(d.services.isEmpty())"No service UUIDs were advertised in the captured packet" else "${d.services.size} service UUID(s) advertised · UUID presence does not prove every service is connectable")
  Detail("Signal evidence",if(d.history.isEmpty())"No live RSSI samples captured" else "${d.history.size} RSSI sample(s) · latest ${d.rssi} dBm · signal varies with distance, obstacles and antenna")
  Detail("Product model / firmware / serial", "Not exposed by the captured Bluetooth advertisement. These values require a documented device protocol or an authenticated device information service.")
  Detail("Network IP / MAC / open ports", "Not available from BLE scanning. Run the separate authorized local-network audit for reachable IP devices.")
 }
 Text("DEVICE INFORMATION",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
 Detail("Detected category",d.category+" — inferred from the advertised name and Bluetooth service identifiers; not a guaranteed product identity")
 Detail("Bluetooth pairing status",when(d.bondState){BluetoothDevice.BOND_BONDED->"Paired (bonded) with this phone";BluetoothDevice.BOND_BONDING->"Pairing in progress";else->"Not paired / no bond recorded"})
 Detail("Bluetooth address",d.address+" — hardware or privacy-randomized radio address reported by Android")
 Detail("Device alias",d.alias.ifBlank{"Not provided by the device or Android"})
 Detail("Bluetooth device class",d.deviceClass?.let{bluetoothClassName(it)+" (code 0x"+it.toString(16).uppercase(Locale.ROOT)+")"}?:"Not reported")
 Detail("Bluetooth major class",d.majorDeviceClass?.let{majorBluetoothClassName(it)+" (code 0x"+it.toString(16).uppercase(Locale.ROOT)+")"}?:"Not reported")
 Detail("Cached Bluetooth service UUIDs",d.cachedUuids.ifEmpty{listOf("No cached Classic SDP UUIDs available; BLE GATT services are listed after connecting")}.joinToString("\n"))
 Detail("First detected",if(d.first>0)date(d.first) else "Not recorded for a paired-only device");Detail("Last detected",if(d.seen>0)date(d.seen) else "Not recorded for a paired-only device");Detail("Signal readings",if(d.history.isNotEmpty())"${d.history.size} captured in this scan" else "No signal readings collected");Detail("Advertised transmit power",d.tx?.let{"$it dBm"}?:"Not provided");Detail("Advertised services",d.services.ifEmpty{listOf("No service UUIDs advertised")}.joinToString("\n"));Detail("Manufacturer-specific advertisement data",d.manufacturer.ifBlank{"Not advertised"});Detail("Service data",d.serviceData.ifBlank{"Not advertised"});Detail("Broadcast bytes (first 64)",d.raw.ifBlank{"Not available"})
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
 if(values.isEmpty()){Text("No signal samples available yet.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);return}
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
