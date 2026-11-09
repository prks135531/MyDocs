package com.monu.mydocs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

private val Ink = Color(0xFF17243A)
private val Blue = Color(0xFF315EF7)
private val Canvas = Color(0xFFF5F7FC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyDocsApp() }
    }
}

@Composable
private fun MyDocsApp() {
    var selectedTab by remember { mutableStateOf("Home") }
    var showScannerInfo by remember { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue,
            onPrimary = Color.White,
            background = Canvas,
            surface = Color.White,
            onSurface = Ink
        )
    ) {
        Scaffold(
            containerColor = Canvas,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    listOf(
                        Triple("Home", Icons.Default.Home, "Home"),
                        Triple("Files", Icons.Default.Folder, "Files"),
                        Triple("Tools", Icons.Default.Tune, "Tools")
                    ).forEach { (label, icon, route) ->
                        NavigationBarItem(
                            selected = selectedTab == route,
                            onClick = { selectedTab = route },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MYDOCS", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text(
                            when (selectedTab) {
                                "Files" -> "Your documents"
                                "Tools" -> "Smart tools"
                                else -> "Document workspace"
                            },
                            color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Ink)
                    }
                }
                Spacer(Modifier.height(22.dp))

                if (selectedTab == "Home") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Ink)
                    ) {
                        Column(Modifier.padding(22.dp)) {
                            Text("Everything, in one place", color = Color.White.copy(alpha = .75f), fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("Scan. Organise. Share.", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(18.dp))
                            Button(
                                onClick = { showScannerInfo = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.DocumentScanner, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Scan a document")
                            }
                        }
                    }
                    Spacer(Modifier.height(26.dp))
                    Text("Quick actions", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    val actions = listOf(
                        "Scan document" to Icons.Default.DocumentScanner,
                        "Import PDF" to Icons.Default.PictureAsPdf,
                        "Import images" to Icons.Default.PhotoLibrary,
                        "Create folder" to Icons.Default.CreateNewFolder
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.height(190.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(actions) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (item.first == "Scan document") showScannerInfo = true
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Icon(item.second, contentDescription = null, tint = Blue, modifier = Modifier.size(26.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text(item.first, color = Ink, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Recent documents", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Your scanned documents will appear here.", color = Color(0xFF788398), fontSize = 14.sp)
                } else {
                    Text(
                        if (selectedTab == "Files") "Your saved PDFs and scans will appear here."
                        else "PDF tools, image enhancement and document utilities.",
                        color = Color(0xFF788398), fontSize = 15.sp
                    )
                }
            }
        }

        if (showScannerInfo) {
            AlertDialog(
                onDismissRequest = { showScannerInfo = false },
                title = { Text("Scanner setup") },
                text = { Text("The new camera scanner is being built as a separate screen with capture, crop, review and export steps.") },
                confirmButton = {
                    TextButton(onClick = { showScannerInfo = false }) { Text("Got it") }
                }
            )
        }
    }
}
