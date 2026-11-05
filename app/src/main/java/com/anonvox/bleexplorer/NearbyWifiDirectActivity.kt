package com.anonvox.bleexplorer

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class NearbyWifiDirectActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  setContent{
   MaterialTheme{
    Scaffold(topBar={TopAppBar(title={Text("CCTV discovery methods")},navigationIcon={TextButton(onClick={finish()}){Text("Back")}})}){
     Column(Modifier.fillMaxSize().padding(it).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),horizontalAlignment=Alignment.Start){
      Icon(Icons.Default.Info,null)
      Text("Wi-Fi Direct discovery is disabled",style=MaterialTheme.typography.titleLarge)
      Text("Wi-Fi Direct frequently returns BUSY errors and is not a dependable way to identify ordinary CCTV cameras. This app no longer relies on it.")
      Text("Use Bluetooth scan for nearby BLE camera advertisements. Use IP Protocol & CCTV discovery for ONVIF WS-Discovery, SSDP/UPnP, RTSP ports and web-management services on your active local network.")
      Text("RTSP is a network service, not a universal radio broadcast. Cameras on unrelated private networks cannot be discovered unless they advertise over a supported nearby radio or are otherwise reachable.")
     }
    }
   }
  }
 }
}
