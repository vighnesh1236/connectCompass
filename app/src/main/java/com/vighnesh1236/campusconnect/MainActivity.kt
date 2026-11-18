package com.vighnesh1236.campusconnect

import android.os.Bundle
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vighnesh1236.campusconnect.data.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

private val Navy = Color(0xFF142044)
private val NavyLight = Color(0xFF263B70)
private val Purple = Color(0xFF7657E8)
private val Cyan = Color(0xFF24B9D7)
private val Canvas = Color(0xFFF4F7FC)
private val Ink = Color(0xFF18233D)
private val Muted = Color(0xFF75809A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStore.initialize(applicationContext)
        window.statusBarColor = android.graphics.Color.rgb(20, 32, 68)
        window.navigationBarColor = android.graphics.Color.rgb(244, 247, 252)
        setContent { CampusConnectTheme { CampusConnectApp() } }
    }
}

@Composable
private fun CampusConnectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Navy, secondary = Purple, tertiary = Cyan,
            background = Canvas, surface = Color.White,
            onBackground = Ink, onSurface = Ink,
            error = Color(0xFFB84555)
        ),
        typography = Typography(
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        ),
        content = content
    )
}

private enum class Tab(val title: String) {
    HOME("Home"), NOTICES("Notices"), EVENTS("Events"), TASKS("Tasks"), PROFILE("Profile")
}

@Composable
private fun CampusConnectApp(vm: CampusViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                vm.saveProfilePhoto(encodeProfilePhoto(context, uri))
            } catch (_: IOException) {
                vm.reportError("Couldn't open that photo. Please choose another image.")
            } catch (_: IllegalArgumentException) {
                vm.reportError("That photo couldn't be processed. Please choose another image.")
            }
        }
    }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            try {
                vm.saveProfilePhoto(encodeProfilePhoto(bitmap))
            } catch (_: IllegalArgumentException) {
                vm.reportError("That photo couldn't be processed. Please take another photo.")
            }
        }
    }
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val selectedTab = Tab.values().firstOrNull { it.name == currentEntry?.destination?.route } ?: Tab.HOME
    var selectedAnnouncement by remember { mutableStateOf<Announcement?>(null) }
    var selectedEvent by remember { mutableStateOf<CampusEvent?>(null) }
    var adminOpen by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf(false) }

    if (!state.authenticated) {
        AuthScreen(state.busy, state.error, vm::authenticate, vm::dismissMessages)
        return
    }
    if (state.restoringSession) {
        SplashScreen()
        return
    }

    Scaffold(
        containerColor = Canvas,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 12.dp) {
                val items = listOf(
                    Tab.HOME to Icons.Rounded.Home,
                    Tab.NOTICES to Icons.Rounded.Campaign,
                    Tab.EVENTS to Icons.Rounded.Event,
                    Tab.TASKS to Icons.Rounded.TaskAlt,
                    Tab.PROFILE to Icons.Rounded.Person
                )
                items.forEach { (item, icon) ->
                    NavigationBarItem(
                        selected = selectedTab == item,
                        onClick = {
                            navController.navigate(item.name) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = item.title) },
                        label = { Text(item.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Purple, selectedTextColor = Purple,
                            indicatorColor = Purple.copy(alpha = .10f)
                        )
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(remember { SnackbarHostState() }) { data ->
                Snackbar(snackbarData = data, containerColor = Navy)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Tab.HOME.name,
                modifier = Modifier.weight(1f)
            ) {
                composable(Tab.HOME.name) {
                    HomeScreen(state, onNavigate = { navController.navigate(it.name) }, onRefresh = vm::refresh)
                }
                composable(Tab.NOTICES.name) {
                    AnnouncementsScreen(state.announcements, vm::refresh) { selectedAnnouncement = it }
                }
                composable(Tab.EVENTS.name) {
                    EventsScreen(state.events, vm::refresh, { selectedEvent = it }) { event, joined ->
                        vm.setEventRegistered(event, joined)
                    }
                }
                composable(Tab.TASKS.name) {
                    AssignmentsScreen(state.assignments, state.busy, vm::setAssignmentCompleted)
                }
                composable(Tab.PROFILE.name) {
                    ProfileScreen(
                        state, onEdit = { editingProfile = true },
                        onAdmin = { adminOpen = true }, onLogout = vm::logout,
                        onTakePhoto = { camera.launch(null) },
                        onChoosePhoto = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemovePhoto = { vm.saveProfilePhoto(null) }
                    )
                }
            }
            AnimatedVisibility(state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple, trackColor = Canvas)
            }
            state.error?.let { InlineMessage(it, true) { vm.dismissMessages() } }
            state.info?.let { InlineMessage(it, false) { vm.dismissMessages() } }
        }
    }

    selectedAnnouncement?.let { announcement ->
        DetailDialog(announcement.title, announcement.category, announcement.description, announcement.createdAt) {
            selectedAnnouncement = null
        }
    }
    selectedEvent?.let { event ->
        DetailDialog(
            event.title, event.venue, event.description,
            "${prettyDate(event.eventDate)}${event.eventTime?.let { " · ${it.take(5)}" } ?: ""}"
        ) { selectedEvent = null }
    }
    if (editingProfile) {
        ProfileEditor(state.user, onDismiss = { editingProfile = false }) { name, roll, department ->
            vm.saveProfile(name, roll, department)
            editingProfile = false
        }
    }
    if (adminOpen) {
        AdminScreen(state, vm, onClose = { adminOpen = false })
    }
}

@Composable
private fun SplashScreen() {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Navy, NavyLight, Purple))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = .14f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.School, null, tint = Color.White, modifier = Modifier.size(42.dp)) }
            Spacer(Modifier.height(18.dp))
            Text("CampusConnect", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text("Your campus, all in one place", color = Color.White.copy(alpha = .72f))
            Spacer(Modifier.height(22.dp))
            CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun AuthScreen(
    busy: Boolean,
    error: String?,
    authenticate: (String, String, String, String, String, Boolean) -> Unit,
    clearError: () -> Unit
) {
    var registering by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var roll by remember { mutableStateOf("") }
    var department by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Canvas)) {
        Box(
            Modifier.fillMaxWidth().height(315.dp)
                .background(Brush.linearGradient(listOf(Navy, NavyLight, Purple)))
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(46.dp))
            Box(
                Modifier.size(66.dp).clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = .14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.School, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("CampusConnect", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text("Your campus, all in one place", color = Color.White.copy(alpha = .78f))
            Spacer(Modifier.height(35.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (registering) "Create your account" else "Welcome back", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (registering) "Join your campus community." else "Sign in to stay on top of campus life.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium
                    )
                    if (registering) {
                        AuthField(name, { name = it }, "Full name", Icons.Rounded.Person)
                    }
                    AuthField(email, { email = it }, "Email address", Icons.Rounded.Email)
                    OutlinedTextField(
                        password, { password = it }, Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, shape = RoundedCornerShape(14.dp)
                    )
                    if (registering) {
                        AuthField(roll, { roll = it }, "Roll number", Icons.Rounded.Badge)
                        AuthField(department, { department = it }, "Department", Icons.Rounded.AccountTree)
                    }
                    (validation ?: error)?.let { InlineMessage(it, true) { validation = null; clearError() } }
                    Button(
                        onClick = {
                            val issue = when {
                                !email.contains("@") -> "Enter a valid college email address."
                                password.length < 8 -> "Password must contain at least 8 characters."
                                registering && name.trim().length < 2 -> "Enter your full name."
                                registering && roll.isBlank() -> "Enter your roll number."
                                registering && department.isBlank() -> "Enter your department."
                                else -> null
                            }
                            validation = issue
                            if (issue == null) authenticate(email, password, name, roll, department, registering)
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        enabled = !busy,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navy)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(21.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text(if (registering) "Create account" else "Sign in", fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = { registering = !registering; validation = null; clearError() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (registering) "Already have an account? Sign in" else "New to campus? Create an account", color = Purple)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Secure access for your campus community", color = Muted, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AuthField(value: String, onChange: (String) -> Unit, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) },
        leadingIcon = { Icon(icon, null) }, singleLine = true, shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun HomeScreen(state: CampusState, onNavigate: (Tab) -> Unit, onRefresh: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(19.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CAMPUSCONNECT", color = Muted, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.6.sp)
                    Text("Good day, ${state.user?.name?.substringBefore(" ") ?: "Student"}", style = MaterialTheme.typography.headlineSmall)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, "Refresh", tint = Navy)
                }
                ProfileAvatar(
                    state.user?.profilePhoto, (state.user?.name?.firstOrNull() ?: 'S').uppercase(),
                    42.dp, Purple.copy(alpha = .12f), Purple
                )
            }
        }
        item { DashboardHero(state.user?.department ?: "Your campus, connected.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    Modifier.weight(1f), Icons.Rounded.EventAvailable,
                    state.events.count { it.registered }.toString(), "Events joined", Cyan
                )
                MetricCard(
                    Modifier.weight(1f), Icons.Rounded.AssignmentTurnedIn,
                    state.assignments.count { it.status == "pending" }.toString(), "Tasks pending", Purple
                )
            }
        }
        item {
            SectionHeading("Quick access", "Explore", { onNavigate(Tab.EVENTS) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                QuickAccess(Modifier.weight(1f), Icons.Rounded.Campaign, "Notices", "Campus updates", Purple) { onNavigate(Tab.NOTICES) }
                QuickAccess(Modifier.weight(1f), Icons.Rounded.Event, "Events", "What's happening", Cyan) { onNavigate(Tab.EVENTS) }
                QuickAccess(Modifier.weight(1f), Icons.Rounded.TaskAlt, "Assignments", "Stay on track", Color(0xFFEF9F55)) { onNavigate(Tab.TASKS) }
            }
        }
        item {
            SectionHeading("Latest announcements", "See all", { onNavigate(Tab.NOTICES) })
        }
        if (state.announcements.isEmpty()) {
            item { EmptyCard("No announcements yet", "New campus updates will show up here.", Icons.Rounded.Campaign) }
        } else {
            items(state.announcements.take(3), key = { "notice-${it.id}" }) { AnnouncementCard(it) {} }
        }
        item {
            SectionHeading("Coming up", "All events", { onNavigate(Tab.EVENTS) })
        }
        if (state.events.isEmpty()) {
            item { EmptyCard("No upcoming events", "Check back soon for campus events.", Icons.Rounded.Event) }
        } else {
            items(state.events.take(2), key = { "event-${it.id}" }) { EventCard(it, {}, {}) }
        }
    }
}

@Composable
private fun DashboardHero(department: String) {
    Card(shape = RoundedCornerShape(25.dp), colors = CardDefaults.cardColors(containerColor = Navy)) {
        Box(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Navy, NavyLight, Color(0xFF4A4A9D))))
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your campus day,\nmade simpler.", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(7.dp))
                    Text(department, color = Color.White.copy(alpha = .76f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(68.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFF84E7F5), modifier = Modifier.size(34.dp)) }
            }
        }
    }
}

@Composable
private fun MetricCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, accent: Color) {
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun QuickAccess(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    Card(
        modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(35.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AnnouncementsScreen(items: List<Announcement>, onRefresh: () -> Unit, onSelect: (Announcement) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("Campus updates", "Announcements from your college", onRefresh) }
        if (items.isEmpty()) item { EmptyCard("It's quiet for now", "College announcements will appear here when published.", Icons.Rounded.Campaign) }
        items(items, key = { it.id }) { AnnouncementCard(it) { onSelect(it) } }
    }
}

@Composable
private fun EventsScreen(
    events: List<CampusEvent>,
    onRefresh: () -> Unit,
    onSelect: (CampusEvent) -> Unit,
    onRegistration: (CampusEvent, Boolean) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("Campus events", "Find your next campus moment", onRefresh) }
        if (events.isEmpty()) item { EmptyCard("Nothing on the calendar yet", "New college events will appear here.", Icons.Rounded.Event) }
        items(events, key = { it.id }) { event ->
            EventCard(event, { onSelect(event) }) { onRegistration(event, !event.registered) }
        }
    }
}

@Composable
private fun AssignmentsScreen(assignments: List<Assignment>, busy: Boolean, onComplete: (Int, Boolean) -> Unit) {
    val pending = assignments.filter { it.status != "completed" }
    val completed = assignments.filter { it.status == "completed" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { ScreenHeader("Assignments", "A clear view of what's due", null) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill("${pending.size} pending", Color(0xFFFFF1E0), Color(0xFFB87020))
                StatusPill("${completed.size} completed", Color(0xFFE6F7F1), Color(0xFF278666))
            }
        }
        if (assignments.isEmpty()) item { EmptyCard("You're all caught up", "New assignments from faculty will show here.", Icons.Rounded.TaskAlt) }
        if (pending.isNotEmpty()) item { Text("To do", style = MaterialTheme.typography.titleLarge) }
        items(pending, key = { "pending-${it.id}" }) { assignment ->
            AssignmentCard(assignment, busy) { onComplete(assignment.id, assignment.status != "completed") }
        }
        if (completed.isNotEmpty()) item { Text("Completed", style = MaterialTheme.typography.titleLarge) }
        items(completed, key = { "completed-${it.id}" }) { assignment ->
            AssignmentCard(assignment, busy) { onComplete(assignment.id, assignment.status != "completed") }
        }
    }
}

@Composable
private fun ProfileScreen(
    state: CampusState,
    onEdit: () -> Unit,
    onAdmin: () -> Unit,
    onLogout: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit
) {
    val user = state.user
    var photoOptionsOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenHeader("Your profile", "Your CampusConnect account", null)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Navy)) {
            Column(
                Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Navy, NavyLight)))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ProfileAvatar(
                    user?.profilePhoto, (user?.name?.firstOrNull() ?: 'S').uppercase(),
                    76.dp, Color.White.copy(alpha = .14f), Color.White
                )
                Spacer(Modifier.height(12.dp))
                Text(user?.name ?: "Student", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(user?.email ?: "", color = Color.White.copy(alpha = .75f))
                Spacer(Modifier.height(9.dp))
                StatusPill((user?.role ?: "student").uppercase(), Color.White.copy(alpha = .13f), Color.White)
                TextButton(onClick = { photoOptionsOpen = true }) {
                    Icon(Icons.Rounded.PhotoCamera, null, tint = Color.White)
                    Spacer(Modifier.width(7.dp))
                    Text(if (user?.profilePhoto == null) "Add profile photo" else "Change profile photo", color = Color.White)
                }
            }
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(horizontal = 17.dp)) {
                ProfileRow(Icons.Rounded.Badge, "Roll number", user?.rollNumber ?: "Not set")
                HorizontalDivider(color = Canvas)
                ProfileRow(Icons.Rounded.AccountTree, "Department", user?.department ?: "Not set")
                HorizontalDivider(color = Canvas)
                ProfileRow(Icons.Rounded.TaskAlt, "Assignments completed", "${state.assignments.count { it.status == "completed" }} of ${state.assignments.size}")
                HorizontalDivider(color = Canvas)
                ProfileRow(Icons.Rounded.EventAvailable, "Events registered", "${state.events.count { it.registered }}")
            }
        }
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) {
            Icon(Icons.Rounded.Edit, null); Spacer(Modifier.width(8.dp)); Text("Edit profile")
        }
        if (user?.role == "admin") {
            Button(onClick = onAdmin, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                Icon(Icons.Rounded.AdminPanelSettings, null); Spacer(Modifier.width(8.dp)); Text("Admin management")
            }
        }
        TextButton(onClick = onLogout, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Rounded.Logout, null, tint = Color(0xFFB84555)); Spacer(Modifier.width(7.dp))
            Text("Sign out", color = Color(0xFFB84555))
        }
    }
    if (photoOptionsOpen) {
        AlertDialog(
            onDismissRequest = { photoOptionsOpen = false },
            title = { Text("Profile photo") },
            text = {
                Column {
                    TextButton(onClick = { photoOptionsOpen = false; onTakePhoto() }) {
                        Icon(Icons.Rounded.PhotoCamera, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Take a photo")
                    }
                    TextButton(onClick = { photoOptionsOpen = false; onChoosePhoto() }) {
                        Icon(Icons.Rounded.PhotoLibrary, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose from photos")
                    }
                    if (user?.profilePhoto != null) {
                        TextButton(onClick = { photoOptionsOpen = false; onRemovePhoto() }) {
                            Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Remove photo", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { photoOptionsOpen = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProfileAvatar(
    photoData: String?,
    fallback: String,
    size: androidx.compose.ui.unit.Dp,
    background: Color,
    foreground: Color
) {
    val photo = remember(photoData) { decodeProfilePhoto(photoData) }
    Box(
        Modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) {
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "Profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(fallback, color = foreground, fontWeight = FontWeight.Bold)
        }
    }
}

private fun decodeProfilePhoto(photoData: String?): Bitmap? {
    if (photoData.isNullOrBlank()) return null
    return runCatching {
        val encoded = photoData.substringAfter(',', missingDelimiterValue = "")
        Base64.decode(encoded, Base64.DEFAULT).let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()
}

private fun encodeProfilePhoto(context: Context, uri: Uri): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    } ?: throw IOException("Unable to read selected image")
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid image")
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 2048) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: throw IOException("Unable to decode selected image")
    return encodeProfilePhoto(bitmap)
}

private fun encodeProfilePhoto(bitmap: Bitmap): String {
    var candidate = bitmap
    val largestDimension = maxOf(bitmap.width, bitmap.height)
    if (largestDimension > 1024) {
        val scale = 1024f / largestDimension
        candidate = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
        )
    }
    try {
        repeat(4) {
            for (quality in listOf(85, 75, 65, 55, 45)) {
                val output = ByteArrayOutputStream()
                if (!candidate.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    throw IllegalArgumentException("Unable to encode image")
                }
                val bytes = output.toByteArray()
                if (bytes.size <= 480 * 1024) {
                    return "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
                }
            }
            val smaller = Bitmap.createScaledBitmap(
                candidate, (candidate.width / 2).coerceAtLeast(1),
                (candidate.height / 2).coerceAtLeast(1), true
            )
            if (candidate !== bitmap) candidate.recycle()
            candidate = smaller
        }
    } finally {
        if (candidate !== bitmap) candidate.recycle()
    }
    throw IllegalArgumentException("Image is too large")
}

@Composable
private fun AdminScreen(state: CampusState, vm: CampusViewModel, onClose: () -> Unit) {
    var editor by remember { mutableStateOf<AdminEditor?>(null) }
    var studentsShowing by remember { mutableStateOf(false) }
    var registrationsShowing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(
            Modifier.fillMaxWidth().background(Navy).padding(horizontal = 12.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("Admin studio", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text("Manage your campus community", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.AdminPanelSettings, null, tint = Cyan, modifier = Modifier.padding(end = 14.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(Modifier.weight(1f), Icons.Rounded.Groups, state.students.size.toString(), "Students", Purple)
                    MetricCard(Modifier.weight(1f), Icons.Rounded.EventAvailable, state.events.size.toString(), "Events", Cyan)
                }
            }
            item { Text("Create and manage", style = MaterialTheme.typography.titleLarge) }
            item { AdminAction(Icons.Rounded.Campaign, "Announcement", "Share a campus update", Purple) { editor = AdminEditor.ANNOUNCEMENT } }
            item { AdminAction(Icons.Rounded.Event, "College event", "Put something on the calendar", Cyan) { editor = AdminEditor.EVENT } }
            item { AdminAction(Icons.Rounded.Assignment, "Assignment", "Add work for your students", Color(0xFFEF9F55)) { editor = AdminEditor.ASSIGNMENT } }
            item { AdminAction(Icons.Rounded.Groups, "Registered students", "${state.students.size} student accounts", Purple) { studentsShowing = !studentsShowing } }
            if (studentsShowing) {
                if (state.students.isEmpty()) item { EmptyCard("No students to show", "Student accounts appear here after registration.", Icons.Rounded.Groups) }
                items(state.students, key = { "student-${it.id}" }) {
                    SimpleInfoCard(it.name, "${it.rollNumber ?: "No roll number"} · ${it.department ?: ""}", Icons.Rounded.Person)
                }
            }
            item { AdminAction(Icons.Rounded.EventAvailable, "Event registrations", "${state.eventRegistrations.size} registrations", Cyan) { registrationsShowing = !registrationsShowing } }
            if (registrationsShowing) {
                if (state.eventRegistrations.isEmpty()) item { EmptyCard("No registrations yet", "Student event registrations will show here.", Icons.Rounded.EventAvailable) }
                items(state.eventRegistrations, key = { "reg-${it.id}" }) {
                    SimpleInfoCard(it.studentName, "${it.eventTitle} · ${it.rollNumber}", Icons.Rounded.EventAvailable)
                }
            }
            item { Text("Your announcements", style = MaterialTheme.typography.titleLarge) }
            items(state.announcements, key = { "admin-notice-${it.id}" }) { announcement ->
                AdminManagedRow(announcement.title, announcement.category, onEdit = {
                    editor = AdminEditor.ANNOUNCEMENT.copy(id = announcement.id, title = announcement.title, description = announcement.description, extra = announcement.category)
                }, onDelete = { vm.deleteAnnouncement(announcement.id) })
            }
            item { Text("Your events", style = MaterialTheme.typography.titleLarge) }
            items(state.events, key = { "admin-event-${it.id}" }) { event ->
                AdminManagedRow(event.title, "${prettyDate(event.eventDate)} · ${event.venue}", onEdit = {
                    editor = AdminEditor.EVENT.copy(
                        id = event.id, title = event.title, description = event.description,
                        extra = event.eventDate, secondExtra = event.eventTime.orEmpty(), thirdExtra = event.venue
                    )
                }, onDelete = { vm.deleteEvent(event.id) })
            }
            item { Text("Assignments", style = MaterialTheme.typography.titleLarge) }
            items(state.assignments, key = { "admin-task-${it.id}" }) { assignment ->
                AdminManagedRow(assignment.title, "${assignment.subject} · due ${prettyDate(assignment.deadline)}", onEdit = {
                    editor = AdminEditor.ASSIGNMENT.copy(
                        id = assignment.id, title = assignment.title, description = assignment.description,
                        extra = assignment.subject, secondExtra = assignment.deadline
                    )
                }, onDelete = { vm.deleteAssignment(assignment.id) })
            }
        }
        state.error?.let { InlineMessage(it, true) { vm.dismissMessages() } }
        state.info?.let { InlineMessage(it, false) { vm.dismissMessages() } }
    }
    editor?.let { current ->
        AdminEditorDialog(current, onClose = { editor = null }) { updated ->
            when (updated.type) {
                AdminEditor.ANNOUNCEMENT.type -> vm.saveAnnouncement(updated.id, updated.title, updated.description, updated.extra)
                AdminEditor.EVENT.type -> vm.saveEvent(updated.id, updated.title, updated.description, updated.extra, updated.secondExtra, updated.thirdExtra)
                else -> vm.saveAssignment(updated.id, updated.title, updated.extra, updated.description, updated.secondExtra)
            }
            editor = null
        }
    }
}

private data class AdminEditor(
    val type: String,
    val id: Int? = null,
    val title: String = "",
    val description: String = "",
    val extra: String = "",
    val secondExtra: String = "",
    val thirdExtra: String = ""
) {
    companion object {
        val ANNOUNCEMENT = AdminEditor("Announcement")
        val EVENT = AdminEditor("Event")
        val ASSIGNMENT = AdminEditor("Assignment")
    }
}

@Composable
private fun AdminEditorDialog(editor: AdminEditor, onClose: () -> Unit, onSave: (AdminEditor) -> Unit) {
    var title by remember(editor) { mutableStateOf(editor.title) }
    var description by remember(editor) { mutableStateOf(editor.description) }
    var extra by remember(editor) { mutableStateOf(editor.extra) }
    var second by remember(editor) { mutableStateOf(editor.secondExtra) }
    var third by remember(editor) { mutableStateOf(editor.thirdExtra) }
    var validation by remember { mutableStateOf<String?>(null) }
    val fields = when (editor.type) {
        "Announcement" -> listOf("Title", "Description", "Category")
        "Event" -> listOf("Title", "Description", "Date (YYYY-MM-DD)", "Time (HH:MM)", "Venue")
        else -> listOf("Title", "Description", "Subject", "Deadline (YYYY-MM-DD)")
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${if (editor.id == null) "New" else "Edit"} ${editor.type}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(fields[0]) }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text(fields[1]) }, minLines = 3)
                OutlinedTextField(extra, { extra = it }, label = { Text(fields[2]) }, singleLine = true)
                if (editor.type == "Event" || editor.type == "Assignment") {
                    OutlinedTextField(second, { second = it }, label = { Text(fields[3]) }, singleLine = true)
                }
                if (editor.type == "Event") {
                    OutlinedTextField(third, { third = it }, label = { Text(fields[4]) }, singleLine = true)
                }
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (editor.type == "Event") Text("Use the date format YYYY-MM-DD, for example 2026-12-05.", color = Muted, style = MaterialTheme.typography.labelSmall)
                if (editor.type == "Assignment") Text("Use the date format YYYY-MM-DD, for example 2026-12-05.", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val invalid = when {
                    title.trim().length < 3 -> "Title must have at least 3 characters."
                    description.isBlank() -> "Description is required."
                    editor.type == "Event" && (!validDate(extra) || third.isBlank()) -> "Enter a valid date and venue."
                    editor.type == "Assignment" && (!validDate(second) || extra.isBlank()) -> "Enter a subject and valid deadline."
                    extra.isBlank() -> "Complete all fields."
                    else -> null
                }
                validation = invalid
                if (invalid == null) onSave(editor.copy(title = title, description = description, extra = extra, secondExtra = second, thirdExtra = third))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

private fun validDate(value: String): Boolean = runCatching {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
    formatter.parse(value) != null
}.getOrDefault(false)

@Composable
private fun AdminAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(color.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color)
            }
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
private fun AdminManagedRow(title: String, subtitle: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(start = 15.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit", tint = Purple) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = Color(0xFFB84555)) }
        }
    }
}

@Composable
private fun ProfileEditor(user: User?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(user?.name.orEmpty()) }
    var roll by remember { mutableStateOf(user?.rollNumber.orEmpty()) }
    var department by remember { mutableStateOf(user?.department.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true)
                OutlinedTextField(roll, { roll = it }, label = { Text("Roll number") }, singleLine = true)
                OutlinedTextField(department, { department = it }, label = { Text("Department") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                error = when {
                    name.trim().length < 2 -> "Enter your name."
                    roll.isBlank() -> "Enter your roll number."
                    department.isBlank() -> "Enter your department."
                    else -> null
                }
                if (error == null) onSave(name, roll, department)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AnnouncementCard(item: Announcement, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Purple.copy(alpha = .11f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Campaign, null, tint = Purple, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.category.uppercase(), color = Purple, style = MaterialTheme.typography.labelSmall, letterSpacing = .7.sp)
                    Spacer(Modifier.weight(1f))
                    Text(prettyDate(item.createdAt.take(10)), color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(6.dp))
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(item.description, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EventCard(event: CampusEvent, onClick: () -> Unit, onRegistration: () -> Unit) {
    Card(shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(43.dp).clip(RoundedCornerShape(13.dp)).background(Cyan.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Event, null, tint = Cyan, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(event.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${prettyDate(event.eventDate)}${event.eventTime?.let { " · ${it.take(5)}" } ?: ""}", color = Purple, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(5.dp))
                    Text(event.venue, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(event.description, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClick) { Text("Details", color = Navy) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onRegistration,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (event.registered) Color(0xFFE8F6F1) else Navy, contentColor = if (event.registered) Color(0xFF278666) else Color.White)
                ) { Text(if (event.registered) "Registered" else "Join event") }
            }
        }
    }
}

@Composable
private fun AssignmentCard(assignment: Assignment, busy: Boolean, onToggle: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = assignment.status == "completed",
                onCheckedChange = { onToggle() },
                enabled = !busy,
                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF278666))
            )
            Column(Modifier.weight(1f).padding(start = 5.dp)) {
                Text(assignment.subject.uppercase(), color = Purple, style = MaterialTheme.typography.labelSmall, letterSpacing = .6.sp)
                Text(assignment.title, style = MaterialTheme.typography.titleMedium)
                Text(assignment.description, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(5.dp))
                Text("Due ${prettyDate(assignment.deadline)}", color = if (assignment.status == "completed") Color(0xFF278666) else Color(0xFFB87020), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String, onRefresh: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        onRefresh?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Refresh, "Refresh", tint = Navy) } }
    }
}

@Composable
private fun SectionHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(action, color = Purple, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusPill(label: String, background: Color, foreground: Color) {
    Box(Modifier.clip(RoundedCornerShape(30.dp)).background(background).padding(horizontal = 11.dp, vertical = 6.dp)) {
        Text(label, color = foreground, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Purple.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Purple, modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProfileRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Purple, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SimpleInfoCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Purple)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DetailDialog(title: String, eyebrow: String, description: String, date: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoAwesome, null, tint = Purple) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                StatusPill(eyebrow.uppercase(), Purple.copy(alpha = .1f), Purple)
                Text(description)
                Text(date, color = Muted, style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun InlineMessage(message: String, isError: Boolean, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (isError) Color(0xFFFFECEC) else Color(0xFFE8F6F1))
            .clickable(onClick = onDismiss).padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null, tint = if (isError) Color(0xFFB84555) else Color(0xFF278666), modifier = Modifier.size(18.dp))
        Text(message, Modifier.weight(1f).padding(start = 8.dp), color = if (isError) Color(0xFF9B3848) else Color(0xFF21745A), style = MaterialTheme.typography.bodySmall)
        Icon(Icons.Rounded.Close, "Dismiss", tint = Muted, modifier = Modifier.size(16.dp))
    }
}

private fun prettyDate(raw: String): String {
    if (raw.isBlank()) return "Date to be announced"
    return runCatching {
        val input = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val output = SimpleDateFormat("MMM d, yyyy", Locale.US)
        output.format(input.parse(raw.take(10))!!)
    }.getOrDefault(raw.take(10))
}
