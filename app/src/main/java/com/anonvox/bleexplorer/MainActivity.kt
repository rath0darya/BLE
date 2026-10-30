package com.anonvox.bleexplorer
import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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

private data class Device(val address:String,val name:String,val rssi:Int,val seen:Long,val first:Long,val tx:Int?,val services:List<String>,val manufacturer:String,val serviceData:String,val raw:String,val history:List<Int>) {
 val quality get()=when { rssi>=-55->"Very strong";rssi>=-68->"Strong";rssi>=-80->"Moderate";else->"Weak" }
 val tint get()=when { rssi>=-55->Color(0xFF22C55E);rssi>=-68->Color(0xFF84CC16);rssi>=-80->Color(0xFFF59E0B);else->Color(0xFFEF4444) }
}
class MainActivity:ComponentActivity() {
 private val handler=Handler(Looper.getMainLooper()); private var scanner:BluetoothLeScanner?=null;private var callback:ScanCallback?=null;private var active=false
 private val found=linkedMapOf<String,Device>();private var update:((List<Device>)->Unit)?=null;private var statusUpdate:((Boolean,String)->Unit)?=null
 private val timeout=Runnable{stop("Scan complete")}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{
  var devices by remember{mutableStateOf(emptyList<Device>())};var scanning by remember{mutableStateOf(false)};var status by remember{mutableStateOf("Ready when you are")}
  update={devices=it};statusUpdate={a,s->scanning=a;status=s}
  AppTheme { Screen(devices,scanning,status,{requestScan()},{stop("Scan stopped")},{share(devices)}) }
 }}
 private fun allowed():Boolean {val p=if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION);return p.all{ContextCompat.checkSelfPermission(this,it)==PackageManager.PERMISSION_GRANTED}}
 private fun requestScan(){if(!allowed()){val p=if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION);requestPermissions(p,701);statusUpdate?.invoke(false,"Allow Bluetooth permission, then tap Scan again");return};start()}
 @Deprecated("Android permission callback")
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==701)statusUpdate?.invoke(false,if(grantResults.isNotEmpty()&&grantResults.all{it==PackageManager.PERMISSION_GRANTED})"Permission granted — tap Scan to begin" else "Bluetooth permission is required")}
 private fun start(){if(active)return;try{
  val adapter=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
  if(adapter==null){statusUpdate?.invoke(false,"Bluetooth is not supported");return};if(!adapter.isEnabled){statusUpdate?.invoke(false,"Turn on Bluetooth, then try again");return}
  scanner=adapter.bluetoothLeScanner;if(scanner==null){statusUpdate?.invoke(false,"Bluetooth scanner unavailable");return}
  found.clear();update?.invoke(emptyList())
  callback=object:ScanCallback(){override fun onScanResult(type:Int,result:ScanResult){add(result)};override fun onBatchScanResults(results:MutableList<ScanResult>){results.forEach{add(it)}};override fun onScanFailed(code:Int){runOnUiThread{stop("Scan failed ($code). Try again.")}}}
  scanner?.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback)
  active=true;statusUpdate?.invoke(true,"Scanning nearby devices…");handler.removeCallbacks(timeout);handler.postDelayed(timeout,15000)
 }catch(_:SecurityException){statusUpdate?.invoke(false,"Bluetooth permission was denied")}catch(_:Exception){statusUpdate?.invoke(false,"Could not start scanning")}}
 private fun add(r:ScanResult){val rec=r.scanRecord;val addr=try{r.device.address?:"Unavailable"}catch(_:SecurityException){"Hidden by Android"};val name=rec?.deviceName?.takeIf{it.isNotBlank()}?:try{r.device.name?.takeIf{it.isNotBlank()}}catch(_:SecurityException){null}?:"Unnamed device"
  val services=rec?.serviceUuids?.map{it.uuid.toString()}?:emptyList()
  val m=rec?.manufacturerSpecificData?.let{s->(0 until s.size()).joinToString(" · "){i->"ID "+s.keyAt(i).toString(16)+": "+s.valueAt(i).joinToString(""){byte -> String.format(Locale.US,"%02X",byte.toInt() and 0xFF)}}}.orEmpty()
  val sd=rec?.serviceData?.entries?.joinToString(" · "){entry -> entry.key.uuid+": "+entry.value.joinToString(""){byte -> String.format(Locale.US,"%02X",byte.toInt() and 0xFF)}}.orEmpty()
  val raw=rec?.bytes?.take(64)?.joinToString(" "){byte -> String.format(Locale.US,"%02X",byte.toInt() and 0xFF)}.orEmpty();val now=System.currentTimeMillis()
  synchronized(found){val old=found[addr];found[addr]=Device(addr,name,r.rssi,now,old?.first?:now,rec?.txPowerLevel,services,m,sd,raw,((old?.history?:emptyList())+r.rssi).takeLast(20));val list=found.values.sortedByDescending{it.rssi};runOnUiThread{update?.invoke(list)}}
 }
 private fun stop(message:String){handler.removeCallbacks(timeout);if(active){try{scanner?.stopScan(callback)}catch(_:SecurityException){};active=false};statusUpdate?.invoke(false,message)}
 private fun share(ds:List<Device>){val csv=buildString{appendLine("Name,Address,RSSI,Signal,First seen,Last seen,Services,Manufacturer data,Service data,Raw advertisement");ds.forEach{d->appendLine(listOf(d.name,d.address,d.rssi.toString(),d.quality,date(d.first),date(d.seen),d.services.joinToString(";"),d.manufacturer,d.serviceData,d.raw).joinToString(","){ "\""+it.replace("\"","\"\"")+"\"" })}}
  startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/csv";putExtra(Intent.EXTRA_SUBJECT,"BLE Explorer results");putExtra(Intent.EXTRA_TEXT,csv)},"Share scan results"))
 }
 override fun onDestroy(){stop("App closed");update=null;statusUpdate=null;super.onDestroy()}
}
@Composable private fun AppTheme(content: @Composable () -> Unit){val dark=isSystemInDarkTheme();MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Color(0xFF7DD3FC),secondary=Color(0xFFA5B4FC),background=Color(0xFF0A1020),surface=Color(0xFF121B2D),surfaceVariant=Color(0xFF202B40),onSurface=Color(0xFFF1F5F9),onSurfaceVariant=Color(0xFFB8C5D8))else lightColorScheme(primary=Color(0xFF0369A1),secondary=Color(0xFF4F46E5),background=Color(0xFFF4F7FC),surface=Color.White,surfaceVariant=Color(0xFFE8EEF7),onSurface=Color(0xFF102033),onSurfaceVariant=Color(0xFF52647B)),content=content)}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Screen(devices:List<Device>,active:Boolean,status:String,onScan:()->Unit,onStop:()->Unit,onShare:()->Unit){
 var compact by remember{mutableStateOf(false)};var query by remember{mutableStateOf("")};var filter by remember{mutableStateOf("All")};var selected by remember{mutableStateOf<Device?>(null)};var settings by remember{mutableStateOf(false)}
 val filters=listOf("All","Strong","Weak","Named","Services","Recent")
 val shown=devices.filter{(it.name.contains(query,true)||it.address.contains(query,true))&&when(filter){"Strong"->it.rssi>=-68;"Weak"->it.rssi < -80;"Named"->it.name!="Unnamed device";"Services"->it.services.isNotEmpty();"Recent"->System.currentTimeMillis()-it.seen<5000;else->true}}
 Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={TopAppBar(title={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){
  Box(Modifier.size(43.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF38BDF8),Color(0xFF6366F1)))),contentAlignment=Alignment.Center){Icon(Icons.Default.BluetoothSearching,null,tint=Color.White,modifier=Modifier.size(26.dp))}
  Column{Text("BLE Explorer",fontWeight=FontWeight.Bold,fontSize=20.sp);Text("Bluetooth discovery",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
 }},actions={IconButton(onClick={settings=true}){Icon(Icons.Default.Settings,"Settings")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))}){pad->
 LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
  item{Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(if(isSystemInDarkTheme())listOf(Color(0xFF162B48),Color(0xFF28244F),Color(0xFF122B3D))else listOf(Color(0xFFE0F2FE),Color(0xFFEDE9FE),Color(0xFFE0F7FA)))).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(if(active)"Searching nearby" else "Discover your surroundings",fontSize=21.sp,fontWeight=FontWeight.Bold);Text(if(active)"Listening for Bluetooth broadcasts" else "Find nearby devices without pairing",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.SignalCellularAlt,null,modifier=Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary)}
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("FOUND",devices.size.toString(),Icons.Default.BluetoothSearching,Modifier.weight(1f));Metric("STRONGEST",devices.maxByOrNull{it.rssi}?.let{"${it.rssi} dBm"}?:"—",Icons.Default.SignalCellularAlt,Modifier.weight(1f));Metric("STATUS",if(active)"Live" else "Ready",Icons.Default.CheckCircle,Modifier.weight(1f))}
   Button(onClick=if(active)onStop else onScan,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(17.dp),colors=ButtonDefaults.buttonColors(containerColor=if(active)Color(0xFFDC2626)else MaterialTheme.colorScheme.primary)){Icon(if(active)Icons.Default.Stop else Icons.Default.Refresh,null);Spacer(Modifier.width(8.dp));Text(if(active)"Stop scanning" else if(devices.isEmpty())"Start scan" else "Scan again",fontWeight=FontWeight.Bold)}
   Text(status,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }}
  item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(query,{query=it},modifier=Modifier.weight(1f),placeholder={Text("Search devices")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true,shape=RoundedCornerShape(16.dp));FilledTonalIconButton(onClick={compact=!compact},modifier=Modifier.size(56.dp)){Icon(if(compact)Icons.Default.GridView else Icons.Default.List,"Switch layout")};FilledTonalIconButton(onClick=onShare,modifier=Modifier.size(56.dp),enabled=devices.isNotEmpty()){Icon(Icons.Default.Share,"Share results")}}}
  item{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Icon(Icons.Default.FilterList,null,tint=MaterialTheme.colorScheme.primary);Text("FILTER",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurfaceVariant);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){filters.forEach{f->FilterChip(selected=filter==f,onClick={filter=f},label={Text(f)})}}}}
  item{Column{Text("Nearby devices",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("${shown.size} devices · sorted by signal",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
  if(shown.isEmpty())item{Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surface).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Default.BluetoothSearching,null,modifier=Modifier.size(44.dp),tint=MaterialTheme.colorScheme.primary);Text(if(devices.isEmpty())"No devices found yet" else "No matching devices",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium);Text(if(devices.isEmpty())"Tap Start scan. It stops automatically after 15 seconds." else "Try another search or filter.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}}
  else items(shown,key={it.address}){d->DeviceCard(d,compact){selected=d}}
  item{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){Icon(Icons.Default.Info,null,modifier=Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Text("Only broadcast information is shown. Range and distance vary by hardware and surroundings.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
 }}
 selected?.let{DeviceSheet(it){selected=null}}
 if(settings)AlertDialog(onDismissRequest={settings=false},title={Text("Scan settings")},text={Text("Scans start only when you tap the button, run in the foreground, and stop automatically after 15 seconds. No pairing or background scanning. Android may hide names or addresses for privacy.")},confirmButton={TextButton(onClick={settings=false}){Text("Got it")}})
}
@Composable private fun Metric(label:String,value:String,icon:ImageVector,modifier:Modifier=Modifier){Column(modifier.clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha=.72f)).padding(horizontal=9.dp,vertical=11.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(17.dp));Text(value,fontWeight=FontWeight.Bold,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(label,fontSize=9.sp,letterSpacing=1.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=FontWeight.Bold)}}
@Composable private fun DeviceCard(d:Device,compact:Boolean,click:()->Unit){Surface(Modifier.fillMaxWidth().clickable(onClick=click),shape=RoundedCornerShape(21.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=1.dp,shadowElevation=if(isSystemInDarkTheme())0.dp else 1.dp){
 if(compact)Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){Glyph(d,44);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(d.name,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(d.address,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)};Column(horizontalAlignment=Alignment.End){Text("${d.rssi} dBm",fontWeight=FontWeight.Bold,color=d.tint,fontSize=13.sp);Text(d.quality,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.ChevronRight,null)}
 else Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Glyph(d,52);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(d.name,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(d.name=="Unnamed device")"Name not shared in broadcast" else "Bluetooth Low Energy device",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)}
 Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(d.quality,fontWeight=FontWeight.SemiBold,color=d.tint);Text("Signal strength",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("${d.rssi} dBm",fontWeight=FontWeight.Bold,fontSize=18.sp,color=d.tint)}
 Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)){Box(Modifier.fillMaxWidth(((d.rssi+100).coerceIn(0,70)/70f)).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(d.tint.copy(alpha=.65f),d.tint))))}
 Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){Tag("SEEN ${ago(d.seen)}");if(d.services.isNotEmpty())Tag("${d.services.size} SERVICES");d.tx?.let{Tag("TX $it dBm")}}
 }}
}
@Composable private fun Glyph(d:Device,size:Int){val palettes=listOf(listOf(Color(0xFF0EA5E9),Color(0xFF6366F1)),listOf(Color(0xFF10B981),Color(0xFF0D9488)),listOf(Color(0xFFF59E0B),Color(0xFFEA580C)));Box(Modifier.size(size.dp).clip(RoundedCornerShape(17.dp)).background(Brush.linearGradient(palettes[kotlin.math.abs(d.name.hashCode()%palettes.size)])),contentAlignment=Alignment.Center){Icon(Icons.Default.BluetoothSearching,null,tint=Color.White,modifier=Modifier.size((size*.52).dp))}}
@Composable private fun Tag(s:String){Text(s,Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=8.dp,vertical=5.dp),fontSize=9.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DeviceSheet(d:Device,close:()->Unit){ModalBottomSheet(onDismissRequest=close,containerColor=MaterialTheme.colorScheme.surface){Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Glyph(d,58);Column(Modifier.weight(1f)){Text(d.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("Detected Bluetooth broadcast",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}}
 Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("SIGNAL","${d.rssi} dBm",Icons.Default.SignalCellularAlt,Modifier.weight(1f));Metric("QUALITY",d.quality,Icons.Default.CheckCircle,Modifier.weight(1f))}
 Text("SIGNAL HISTORY",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary); SignalHistory(d.history,d.tint)
 Text("DEVICE INFORMATION",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
 Detail("Device address",d.address);Detail("First detected",date(d.first));Detail("Last detected",date(d.seen));Detail("Signal readings","${d.history.size} captured in this scan");Detail("Advertised transmit power",d.tx?.let{"$it dBm"}?:"Not provided");Detail("Advertised services",d.services.ifEmpty{listOf("No services listed")}.joinToString("\n"));Detail("Manufacturer information",d.manufacturer.ifBlank{"Not provided"});Detail("Service information",d.serviceData.ifBlank{"Not provided"});Detail("Broadcast bytes (first 64)",d.raw.ifBlank{"Not available"})
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
