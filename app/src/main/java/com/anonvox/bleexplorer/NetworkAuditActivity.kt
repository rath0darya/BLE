package com.anonvox.bleexplorer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private data class LanFinding(val ip:String,val ports:List<Int>,val camera:Boolean,val confidence:String,val protocols:List<String>,val risks:List<String>,val details:String)

class NetworkAuditActivity:ComponentActivity() {
 @Volatile private var running=false
 private val pool=Executors.newFixedThreadPool(32)
 private val evidence=java.util.concurrent.ConcurrentHashMap<String,List<String>>()
 private val deviceDetails=java.util.concurrent.ConcurrentHashMap<String,String>()
 private var lock:WifiManager.MulticastLock?=null

 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{NetworkAuditScreen({finish()},{callback->startAudit(callback)},{running=false},{rows->exportReport(rows)})}}

 private fun subnet():String? { return try {
  val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  val network=cm.activeNetwork?:return null
  val properties=cm.getLinkProperties(network)?:return null
  val address=properties.linkAddresses.firstOrNull{it.address is java.net.Inet4Address}?:return null
  val bytes=address.address.address
  if(bytes.size!=4||bytes[0].toInt()==127||bytes[0].toInt() and 255==169)return null
  val prefix=address.prefixLength
  if(prefix<16||prefix>30)return null
  val ip=java.nio.ByteBuffer.wrap(bytes).int
  val mask=(-1 shl (32-prefix))
  val networkAddress=ip and mask
  val a=(networkAddress ushr 24) and 255
  val b=(networkAddress ushr 16) and 255
  val c=(networkAddress ushr 8) and 255
  if(prefix<24) "$a.$b.$c" else "$a.$b.$c"
 } catch(_:Exception){null} }

 private fun startAudit(update:(List<LanFinding>,String,Boolean)->Unit){
  if(running)return
  val base=subnet()
  if(base==null){update(emptyList(),"Connect to a local network first. This audit checks reachable IP services on the active network.",false);return}
  running=true;evidence.clear();deviceDetails.clear()
  update(emptyList(),"Scanning active local network subnet "+base+".0/24. Only local service discovery is performed.",true)
  Thread{
   val results=java.util.Collections.synchronizedList(mutableListOf<LanFinding>())
   val completed=AtomicInteger(0)
   try{
    try{val wm=applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager;lock=wm.createMulticastLock("ble-local-discovery").also{it.setReferenceCounted(false);it.acquire()}}catch(_:Exception){}
    udpProbe()
    for(host in 1..254){
     if(!running)break
     val address=base+"."+host
     pool.submit{
      if(running){inspect(address)?.let{results.add(it)}}
      val count=completed.incrementAndGet()
      if(count%8==0||count==254){val copy=synchronized(results){results.sortedBy{it.ip}};runOnUiThread{update(copy,"Checked "+count+" / 254 addresses · "+copy.size+" devices found",running)}}
     }
    }
    while(completed.get()<254&&running)Thread.sleep(120)
    val copy=synchronized(results){results.sortedBy{it.ip}}
    runOnUiThread{update(copy,if(running)"Audit complete · "+copy.size+" devices found" else "Audit stopped · "+copy.size+" devices found",false)}
   }catch(_:InterruptedException){runOnUiThread{update(synchronized(results){results.toList()},"Audit stopped",false)}}
   finally{running=false;try{lock?.release()}catch(_:Exception){};lock=null}
  }.start()
 }

 private fun udpProbe(){
  val probes=listOf(
   Pair(1900,"M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n"),
   Pair(3702,"<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"><e:Header><w:MessageID>uuid:ble-audit</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>")
  )
  for((port,message) in probes)try{
   DatagramSocket().use{s->
    s.soTimeout=500
    val bytes=message.toByteArray()
    s.send(DatagramPacket(bytes,bytes.size,InetSocketAddress("239.255.255.250",port)))
    val until=System.currentTimeMillis()+900
    while(running&&System.currentTimeMillis()<until)try{
     val packet=DatagramPacket(ByteArray(8192),8192);s.receive(packet)
     val ip=packet.address.hostAddress?:continue
     val body=String(packet.data,0,packet.length)
     val onvif=port==3702||body.contains("NetworkVideoTransmitter",true)
     val label=if(onvif)"ONVIF WS-Discovery" else "UPnP / SSDP"
     evidence[ip]=(evidence[ip].orEmpty()+label).distinct()
     if(onvif){
      val xaddr=Regex("<(?:\\w+:)?XAddrs[^>]*>(.*?)</(?:\\w+:)?XAddrs>",RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.firstOrNull()
      if(xaddr!=null)probeOnvifDetails(ip,xaddr)
     }else{
      val location=Regex("(?im)^LOCATION:\\s*(https?://[^\\s]+)").find(body)?.groupValues?.get(1)
      if(location!=null)readDescription(ip,location)
     }
    }catch(_:SocketTimeoutException){break}
   }
  }catch(_:Exception){}
 }

 private fun xmlValue(xml:String,tag:String):String?{
  val escaped=Regex.escape(tag)
  return Regex("<(?:[A-Za-z0-9_-]+:)?$escaped(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_-]+:)?$escaped>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
   .find(xml)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"),"")?.replace("&amp;","&")?.replace("&lt;","<")?.replace("&gt;",">")?.trim()?.takeIf{it.isNotBlank()}
 }
 private fun readDescription(ip:String,url:String){
  try{
   val conn=URL(url).openConnection() as HttpURLConnection
   conn.connectTimeout=900;conn.readTimeout=900;conn.requestMethod="GET";conn.setRequestProperty("User-Agent","BLE-Explorer/1.2")
   val xml=conn.inputStream.bufferedReader().use{it.readText().take(256*1024)};conn.disconnect()
   val fields=listOf("friendlyName" to "Device", "manufacturer" to "Manufacturer", "modelName" to "Model", "modelNumber" to "Model number")
   val found=fields.mapNotNull{(tag,label)->xmlValue(xml,tag)?.let{"$label: $it"}}
   if(found.isNotEmpty())deviceDetails[ip]=found.joinToString(" · ")
  }catch(_:Exception){}
 }
 private fun probeOnvifDetails(ip:String,endpoint:String){
  try{
   val soap="""<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><s:Body><tds:GetDeviceInformation/></s:Body></s:Envelope>"""
   val conn=URL(endpoint).openConnection() as HttpURLConnection
   conn.connectTimeout=1100;conn.readTimeout=1100;conn.requestMethod="POST";conn.doOutput=true
   conn.setRequestProperty("Content-Type","application/soap+xml; charset=utf-8")
   conn.setRequestProperty("SOAPAction","http://www.onvif.org/ver10/device/wsdl/GetDeviceInformation")
   conn.setRequestProperty("User-Agent","BLE-Explorer/1.2")
   conn.outputStream.use{it.write(soap.toByteArray(Charsets.UTF_8))}
   val xml=(if(conn.responseCode in 200..299)conn.inputStream else conn.errorStream)?.bufferedReader()?.use{it.readText().take(128*1024)}.orEmpty()
   conn.disconnect()
   val fields=listOf("Manufacturer" to "Manufacturer","Model" to "Model","FirmwareVersion" to "Firmware","HardwareId" to "Hardware")
   val found=fields.mapNotNull{(tag,label)->xmlValue(xml,tag)?.let{"$label: $it"}}
   if(found.isNotEmpty())deviceDetails[ip]=found.joinToString(" · ")
  }catch(_:Exception){}
 }

 private fun inspect(ip:String):LanFinding?{
  val candidates=listOf(21,23,80,443,554,8554,8000,8080,8443,8899,37777,34567)
  val open=mutableListOf<Int>()
  for(port in candidates){if(!running)return null;try{Socket().use{it.connect(InetSocketAddress(ip,port),240);open.add(port)}}catch(_:Exception){}}
  val signals=evidence[ip].orEmpty()
  if(open.isEmpty()&&signals.isEmpty())return null
  val protocols=mutableListOf<String>()
  if(80 in open||8000 in open||8080 in open)protocols.add("HTTP")
  if(443 in open||8443 in open)protocols.add("HTTPS")
  if(554 in open||8554 in open)protocols.add("RTSP")
  if(signals.any{it.contains("ONVIF")})protocols.add("ONVIF / WS-Discovery")
  if(signals.any{it.contains("SSDP")})protocols.add("UPnP / SSDP")
  if(21 in open)protocols.add("FTP")
  if(23 in open)protocols.add("Telnet")
  if(8899 in open)protocols.add("Vendor service · 8899")
  if(37777 in open)protocols.add("Possible Dahua service · 37777")
  if(34567 in open)protocols.add("Possible DVR/NVR service · 34567")
  val camera=554 in open||8554 in open||37777 in open||34567 in open||signals.any{it.contains("ONVIF")}
  val risks=mutableListOf<String>()
  if(23 in open)risks.add("HIGH: Telnet is commonly unencrypted. Disable it or restrict management access.")
  if(21 in open)risks.add("REVIEW: FTP may expose credentials/data without encryption. Prefer SFTP/FTPS or disable it.")
  if(80 in open||8080 in open||8000 in open)risks.add("REVIEW: HTTP is reachable. Confirm admin access is protected by HTTPS.")
  if(554 in open||8554 in open)risks.add("REVIEW: RTSP is reachable. Verify stream authentication and restrict access to trusted clients.")
  if(camera&&443 !in open&&8443 !in open)risks.add("REVIEW: no common HTTPS port detected; this does not prove encryption is absent.")
  if(risks.isEmpty()&&camera)risks.add("No obvious exposure found by these limited checks. Firmware and authentication remain unverified.")
  val confidence=when{signals.any{it.contains("ONVIF")}->"High · ONVIF discovery response";554 in open||8554 in open->"High · RTSP service";camera->"Medium · camera-related port";else->"Low · network device"}
  return LanFinding(ip,open,camera,confidence,(protocols+signals).distinct(),risks,deviceDetails[ip].orEmpty())
 }

 
 private fun exportReport(rows:List<LanFinding>){
  val json=org.json.JSONArray().apply{rows.forEach{f->put(org.json.JSONObject().apply{
   put("ip_address",f.ip);put("open_tcp_ports",org.json.JSONArray(f.ports));put("possible_camera_candidate",f.camera)
   put("confidence",f.confidence);put("protocol_indicators",org.json.JSONArray(f.protocols));put("security_review_indicators",org.json.JSONArray(f.risks))
   put("device_details",f.details);put("interpretation","Reachability and protocol indicators only; not proof of identity or vulnerability.")
  })}}.toString(2)
  val text=buildString{
   appendLine("BLE EXPLORER — AUTHORIZED LOCAL NETWORK & CCTV AUDIT")
   appendLine("Generated: "+java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date()))
   appendLine("Devices found: "+rows.size);appendLine("Camera candidates: "+rows.count{it.camera})
   appendLine("Scope: active local subnet service discovery; no credential guessing, stream viewing or exploit execution.")
   rows.forEachIndexed{index,f->
    appendLine();appendLine("DEVICE ${index+1}: ${f.ip}")
    appendLine("Camera candidate: "+f.camera);appendLine("Confidence: "+f.confidence)
    appendLine("Open TCP ports: "+f.ports.joinToString().ifBlank{"None"})
    appendLine("Protocols: "+f.protocols.joinToString().ifBlank{"No signature"})
    appendLine("Device details: "+f.details.ifBlank{"Not exposed"})
    appendLine("Security review:");f.risks.forEach{appendLine("- "+it)}
   }
   appendLine();appendLine("Limitations: an open port is not a confirmed vulnerability. A missing HTTPS port does not prove encryption is absent. No authentication, firmware CVE or internet exposure tests were performed.")
  }
  ReportExporter.shareAll(this,"BLE Explorer local network and CCTV audit","ble-explorer-network-audit",text,json)
 }


 private fun exportReport(rows:List<LanFinding>){
  val json=org.json.JSONArray().apply{rows.forEach{f->put(org.json.JSONObject().apply{
   put("ip_address",f.ip);put("open_tcp_ports",org.json.JSONArray(f.ports));put("possible_camera_candidate",f.camera)
   put("confidence",f.confidence);put("protocol_indicators",org.json.JSONArray(f.protocols));put("security_review_indicators",org.json.JSONArray(f.risks))
   put("device_details",f.details);put("interpretation","Reachability and protocol indicators only; not proof of identity or vulnerability.")
  })}}.toString(2)
  val text=buildString{
   appendLine("BLE EXPLORER — AUTHORIZED LOCAL NETWORK & CCTV AUDIT")
   appendLine("Generated: "+java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date()))
   appendLine("Devices found: "+rows.size);appendLine("Camera candidates: "+rows.count{it.camera})
   appendLine("Scope: active local subnet service discovery; no credential guessing, stream viewing or exploit execution.")
   rows.forEachIndexed{index,f->
    appendLine();appendLine("DEVICE ${index+1}: ${f.ip}")
    appendLine("Camera candidate: "+f.camera);appendLine("Confidence: "+f.confidence)
    appendLine("Open TCP ports: "+f.ports.joinToString().ifBlank{"None"})
    appendLine("Protocols: "+f.protocols.joinToString().ifBlank{"No signature"})
    appendLine("Device details: "+f.details.ifBlank{"Not exposed"})
    appendLine("Security review:");f.risks.forEach{appendLine("- "+it)}
   }
   appendLine();appendLine("Limitations: an open port is not a confirmed vulnerability. A missing HTTPS port does not prove encryption is absent. No authentication, firmware CVE or internet exposure tests were performed.")
  }
  ReportExporter.shareAll(this,"BLE Explorer local network and CCTV audit","ble-explorer-network-audit",text,json)
 }

 override fun onDestroy(){running=false;try{lock?.release()}catch(_:Exception){};pool.shutdownNow();super.onDestroy()}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NetworkAuditScreen(onBack:()->Unit,onScan: ((List<LanFinding>, String, Boolean) -> Unit) -> Unit,onStop:()->Unit,onExport:(List<LanFinding>)->Unit){
 var rows by remember{mutableStateOf(emptyList<LanFinding>())}
 var status by remember{mutableStateOf("Ready · connect to Wi-Fi and start an audit")}
 var active by remember{mutableStateOf(false)}
 var onlyCameras by remember{mutableStateOf(false)}
 val shown=rows.filter{!onlyCameras||it.camera}
 MaterialTheme(colorScheme=if(androidx.compose.foundation.isSystemInDarkTheme())darkColorScheme(primary=Color(0xFFFBDA95),secondary=Color(0xFFDAA96A),background=Color(0xFF231610),surface=Color(0xFF35241F),onSurface=Color(0xFFF1E7E2),onSurfaceVariant=Color(0xFFD2BDB1))else lightColorScheme(primary=Color(0xFF9D320C),secondary=Color(0xFFB8521D),background=Color(0xFFFFF7EF),surface=Color(0xFFFFFAF6),onSurface=Color(0xFF5A2D1D),onSurfaceVariant=Color(0xFF806457))){
  Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={TopAppBar(title={Column{Text("Network & CCTV audit",fontWeight=FontWeight.Bold);Text("Authorized active local network",style=MaterialTheme.typography.labelSmall)}},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))}){padding->
   LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    item{Column(Modifier.fillMaxWidth().background(Color(0xFF5A2D1D),RoundedCornerShape(22.dp)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
     Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Security,null,tint=Color(0xFFFBDA95));Spacer(Modifier.width(9.dp));Text("Local network security",fontSize=20.sp,fontWeight=FontWeight.Bold)}
     Text("Discover reachable IP cameras, DVRs, NVRs and protocol endpoints on the active local network. Bluetooth scanning remains independent of Wi-Fi. RTSP, ONVIF, SSDP and web services are checked without logging in or exploiting devices.",color=Color(0xFFFFE9D2),style=MaterialTheme.typography.bodyMedium)
     Text("DEVICES  "+rows.size+"     CAMERAS  "+rows.count{it.camera}+"     FINDINGS  "+rows.sumOf{it.risks.size},fontWeight=FontWeight.Bold,color=Color.White)
     Button(onClick={if(active)onStop()else onScan{list,message,busy->rows=list;status=message;active=busy}},modifier=Modifier.fillMaxWidth()){Icon(if(active)Icons.Default.Security else Icons.Default.Refresh,null);Spacer(Modifier.width(8.dp));Text(if(active)"Stop audit" else "Start local network audit")}
     OutlinedButton(onClick={onExport(rows)},modifier=Modifier.fillMaxWidth(),enabled=rows.isNotEmpty()){Icon(Icons.Default.Share,null);Spacer(Modifier.width(8.dp));Text("Export TXT · JSON · PDF report")}
     Text(status,style=MaterialTheme.typography.labelSmall,color=Color(0xFFFFE9D2))
    }}
    item{Row(verticalAlignment=Alignment.CenterVertically){Text("Show CCTV candidates only",modifier=Modifier.weight(1f));Switch(checked=onlyCameras,onCheckedChange={onlyCameras=it})}}
    if(shown.isEmpty())item{Text(if(rows.isEmpty())"No results yet. Start a scan while connected to your own or administered Wi-Fi." else "No camera candidates found.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
    items(shown,key={it.ip}){f->Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
     Row(verticalAlignment=Alignment.CenterVertically){Icon(if(f.camera)Icons.Default.CameraAlt else Icons.Default.Security,null,tint=if(f.camera)Color(0xFFFBDA95)else Color(0xFFDAA96A));Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f)){Text(f.ip,fontWeight=FontWeight.Bold);Text(if(f.camera)"Possible CCTV / IP camera" else "Network device",style=MaterialTheme.typography.labelSmall)}}
     Text(f.confidence,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     if(f.details.isNotBlank())Text(f.details,style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold,color=Color(0xFFFBDA95))
     else if(f.camera)Text("Device model details were not exposed without authentication.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     Text("Open TCP ports: "+f.ports.joinToString().ifBlank{"None"},style=MaterialTheme.typography.bodySmall)
     Text("Protocols: "+f.protocols.joinToString().ifBlank{"No protocol signature"},style=MaterialTheme.typography.bodySmall)
     Text("Security review",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.labelMedium)
     f.risks.forEach{risk->Text("• "+risk,style=MaterialTheme.typography.bodySmall,color=if(risk.startsWith("HIGH"))Color(0xFFFCA5A5)else Color(0xFFFCD34D))}
    }}}
    item{Text("Limitations: discovery is heuristic unless ONVIF responds. Open ports indicate reachability, not confirmed vulnerabilities. This tool does not test credentials, firmware CVEs, anonymous streams, cloud exposure or exploitability. “No obvious risk” is not a security certification.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
   }
  }
 }
}
