package com.astral.font

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private var importedFontUri: Uri? = null
    private var importedFontName: String = ""

    private lateinit var textImportedFontName: TextView
    private lateinit var btnImport: Button
    private lateinit var btnConvert: Button
    private lateinit var checkItalic: MaterialCheckBox
    private lateinit var checkBold: MaterialCheckBox
    private lateinit var checkBoldItalic: MaterialCheckBox
    private lateinit var layoutProgress: LinearLayout
    private lateinit var textProgressStatus: TextView

    // Register file picker launcher for .ttf and .otf files
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            importedFontUri = uri
            importedFontName = getFileNameFromUri(uri) ?: "font_file.ttf"
            textImportedFontName.text = importedFontName
            showSnackbar("Font imported successfully: $importedFontName")
        }
    }

    // Register permission launcher for older Android versions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startConversionProcess()
        } else {
            showSnackbar("Storage permission is required to save fonts on older Android versions.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        textImportedFontName = findViewById(R.id.textImportedFontName)
        btnImport = findViewById(R.id.btnImport)
        btnConvert = findViewById(R.id.btnConvert)
        checkItalic = findViewById(R.id.checkItalic)
        checkBold = findViewById(R.id.checkBold)
        checkBoldItalic = findViewById(R.id.checkBoldItalic)
        layoutProgress = findViewById(R.id.layoutProgress)
        textProgressStatus = findViewById(R.id.textProgressStatus)

        // Set up click listeners
        btnImport.setOnClickListener {
            // Pick ttf or otf files
            filePickerLauncher.launch("*/*")
        }

        btnConvert.setOnClickListener {
            handleConvertClick()
        }
    }

    private fun handleConvertClick() {
        if (importedFontUri == null) {
            showSnackbar("Please import a font file (.ttf or .otf) first.")
            return
        }

        if (!checkItalic.isChecked && !checkBold.isChecked && !checkBoldItalic.isChecked) {
            showSnackbar("Please select at least one style to convert.")
            return
        }

        // On Android versions older than 10 (API < 29), we need to request WRITE_EXTERNAL_STORAGE permission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(permission)
            } else {
                startConversionProcess()
            }
        } else {
            startConversionProcess()
        }
    }

    private fun startConversionProcess() {
        val uri = importedFontUri ?: return
        val baseName = importedFontName

        val targetStyles = ArrayList<FontConverter.Style>()
        if (checkItalic.isChecked) targetStyles.add(FontConverter.Style.ITALIC)
        if (checkBold.isChecked) targetStyles.add(FontConverter.Style.BOLD)
        if (checkBoldItalic.isChecked) targetStyles.add(FontConverter.Style.BOLD_ITALIC)

        // Update UI state
        setUIEnabled(false)
        layoutProgress.visibility = View.VISIBLE
        textProgressStatus.text = "Converting font, please wait..."

        // Launch background conversion process using Coroutines
        CoroutineScope(Dispatchers.Main).launch {
            var successCount = 0
            val savedFiles = ArrayList<String>()

            for (style in targetStyles) {
                textProgressStatus.text = "Converting to ${style.name.replace("_", " ")}..."
                val savedUri = convertAndSaveFont(uri, style, baseName)
                if (savedUri != null) {
                    successCount++
                    savedFiles.add(getFileNameFromUri(savedUri) ?: style.name)
                }
            }

            // Reset UI state
            setUIEnabled(true)
            layoutProgress.visibility = View.GONE

            if (successCount == targetStyles.size) {
                val fileListStr = savedFiles.joinToString(", ")
                showLongToast("Successfully converted all styles: $fileListStr\nSaved in Downloads/AstralFont")
            } else if (successCount > 0) {
                showLongToast("Converted $successCount of ${targetStyles.size} styles. Saved in Downloads/AstralFont")
            } else {
                showSnackbar("Failed to convert font. Please make sure it's a valid TTF/OTF file.")
            }
        }
    }

    private suspend fun convertAndSaveFont(
        sourceUri: Uri,
        style: FontConverter.Style,
        baseName: String
    ): Uri? = withContext(Dispatchers.IO) {
        val suffix = when (style) {
            FontConverter.Style.ITALIC -> "-Italic"
            FontConverter.Style.BOLD -> "-Bold"
            FontConverter.Style.BOLD_ITALIC -> "-BoldItalic"
        }

        val ext = if (baseName.endsWith(".otf", ignoreCase = true)) "otf" else "ttf"
        val pureName = baseName.substringBeforeLast(".")
        val outFileName = "$pureName$suffix.$ext"

        val resolver = contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, outFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, if (ext == "otf") "font/otf" else "font/ttf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AstralFont")
            }
            // Use MediaStore.Downloads.EXTERNAL_CONTENT_URI on Android 10+
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { output ->
                        resolver.openInputStream(sourceUri)?.use { input ->
                            FontConverter.convertFont(input, output, style)
                        }
                    }
                    uri
                } catch (e: Exception) {
                    e.printStackTrace()
                    try {
                        resolver.delete(uri, null, null)
                    } catch (ex: Exception) {
                        // ignore
                    }
                    null
                }
            } else {
                null
            }
        } else {
            val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(docDir, "AstralFont")
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, outFileName)
            try {
                FileOutputStream(targetFile).use { output ->
                    resolver.openInputStream(sourceUri)?.use { input ->
                        FontConverter.convertFont(input, output, style)
                    }
                }
                Uri.fromFile(targetFile)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun setUIEnabled(enabled: Boolean) {
        btnImport.isEnabled = enabled
        btnConvert.isEnabled = enabled
        checkItalic.isEnabled = enabled
        checkBold.isEnabled = enabled
        checkBoldItalic.isEnabled = enabled
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }

    private fun showSnackbar(message: String) {
        val rootView = findViewById<View>(android.R.id.content)
        if (rootView != null) {
            Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showLongToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
