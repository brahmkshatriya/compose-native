package dev.brahmkshatriya.compose

import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal fun windowsMsiSource(
    modernWix: Boolean,
    applicationName: String,
    packageName: String,
    packageVersion: String,
    description: String,
    vendor: String,
    distributionDirectory: File,
): String {
    val files =
        distributionDirectory
            .walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(distributionDirectory).invariantSeparatorsPath }
            .toList()
    check(files.isNotEmpty()) { "Windows distribution is empty: " + distributionDirectory }

    val manufacturer = vendor.ifBlank { applicationName }
    val version = windowsMsiVersion(packageVersion)
    val upgradeCode =
        UUID.nameUUIDFromBytes(packageName.toByteArray(Charsets.UTF_8)).toString().uppercase()

    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    if (modernWix) {
        sb.append("<Wix xmlns=\"http://wixtoolset.org/schemas/v4/wxs\">\n")
        sb.append("  <Package Name=\"").append(xmlEscape(applicationName)).append("\"")
        sb.append(" Manufacturer=\"").append(xmlEscape(manufacturer)).append("\"")
        sb.append(" Version=\"").append(xmlEscape(version)).append("\"")
        sb.append(" UpgradeCode=\"").append(upgradeCode).append("\"")
        sb.append(" Language=\"1033\" InstallerVersion=\"500\" Scope=\"perMachine\">\n")
        sb.append(
            "    <MajorUpgrade DowngradeErrorMessage=\"A newer version is already installed.\" />\n"
        )
        sb.append("    <MediaTemplate EmbedCab=\"yes\" />\n")
        if (description.isNotBlank()) {
            sb.append("    <Property Id=\"ARPCOMMENTS\" Value=\"")
                .append(xmlEscape(description))
                .append("\" />\n")
        }
        sb.append("    <StandardDirectory Id=\"ProgramFiles64Folder\">\n")
        sb.append("      <Directory Id=\"INSTALLFOLDER\" Name=\"")
            .append(xmlEscape(applicationName.fileSafe()))
            .append("\">\n")
        appendWixDirectoryContents(
            sb,
            distributionDirectory,
            distributionDirectory,
            files,
            "        ",
        )
        sb.append("      </Directory>\n")
        sb.append("    </StandardDirectory>\n")
    } else {
        sb.append("<Wix xmlns=\"http://schemas.microsoft.com/wix/2006/wi\">\n")
        sb.append("  <Product Id=\"*\" Name=\"").append(xmlEscape(applicationName)).append("\"")
        sb.append(" Language=\"1033\" Version=\"").append(xmlEscape(version)).append("\"")
        sb.append(" Manufacturer=\"").append(xmlEscape(manufacturer)).append("\"")
        sb.append(" UpgradeCode=\"").append(upgradeCode).append("\">\n")
        sb.append("    <Package InstallerVersion=\"500\" Compressed=\"yes\"")
            .append(" InstallScope=\"perMachine\" Platform=\"x64\" />\n")
        sb.append(
            "    <MajorUpgrade DowngradeErrorMessage=\"A newer version is already installed.\" />\n"
        )
        sb.append("    <MediaTemplate EmbedCab=\"yes\" />\n")
        if (description.isNotBlank()) {
            sb.append("    <Property Id=\"ARPCOMMENTS\" Value=\"")
                .append(xmlEscape(description))
                .append("\" />\n")
        }
        sb.append("    <Directory Id=\"TARGETDIR\" Name=\"SourceDir\">\n")
        sb.append("      <Directory Id=\"ProgramFiles64Folder\">\n")
        sb.append("        <Directory Id=\"INSTALLFOLDER\" Name=\"")
            .append(xmlEscape(applicationName.fileSafe()))
            .append("\">\n")
        appendWixDirectoryContents(
            sb,
            distributionDirectory,
            distributionDirectory,
            files,
            "          ",
        )
        sb.append("        </Directory>\n")
        sb.append("      </Directory>\n")
        sb.append("    </Directory>\n")
    }

    sb.append("    <Feature Id=\"MainFeature\" Title=\"")
        .append(xmlEscape(applicationName))
        .append("\" Level=\"1\">\n")
    files.forEach { file ->
        val relative = file.relativeTo(distributionDirectory).invariantSeparatorsPath
        sb.append("      <ComponentRef Id=\"cmp_")
            .append(stableInstallerId(relative))
            .append("\" />\n")
    }
    sb.append("    </Feature>\n")
    sb.append(if (modernWix) "  </Package>\n" else "  </Product>\n")
    sb.append("</Wix>\n")
    return sb.toString()
}

private fun appendWixDirectoryContents(
    sb: StringBuilder,
    root: File,
    directory: File,
    files: List<File>,
    indent: String,
) {
    files
        .filter { it.parentFile == directory }
        .forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val id = stableInstallerId(relative)
            sb.append(indent).append("<Component Id=\"cmp_").append(id).append("\" Guid=\"*\">\n")
            sb.append(indent)
                .append("  <File Id=\"fil_")
                .append(id)
                .append("\" Source=\"")
                .append(xmlEscape(file.absolutePath))
                .append("\" KeyPath=\"yes\" />\n")
            sb.append(indent).append("</Component>\n")
        }
    directory.listFiles().orEmpty().filter(File::isDirectory).sortedBy(File::getName).forEach {
        child ->
        val relative = child.relativeTo(root).invariantSeparatorsPath
        sb.append(indent)
            .append("<Directory Id=\"dir_")
            .append(stableInstallerId(relative))
            .append("\" Name=\"")
            .append(xmlEscape(child.name))
            .append("\">\n")
        appendWixDirectoryContents(sb, root, child, files, indent + "  ")
        sb.append(indent).append("</Directory>\n")
    }
}

internal fun windowsNsisSource(
    applicationName: String,
    packageName: String,
    executableName: String,
    packageVersion: String,
    description: String,
    vendor: String,
    distributionDirectory: File,
    outputFile: File,
): String {
    val app = nsisEscape(applicationName)
    val manufacturer = nsisEscape(vendor.ifBlank { applicationName })
    val uninstallKey =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + nsisEscape(packageName)
    val sb = StringBuilder()
    sb.append("Unicode true\n")
    sb.append("Name \"").append(app).append("\"\n")
    sb.append("OutFile \"").append(nsisEscape(outputFile.absolutePath)).append("\"\n")
    sb.append("InstallDir \"\$PROGRAMFILES64\\")
        .append(nsisEscape(applicationName.fileSafe()))
        .append("\"\n")
    sb.append("InstallDirRegKey HKLM \"").append(uninstallKey).append("\" \"InstallLocation\"\n")
    sb.append("RequestExecutionLevel admin\n")
    sb.append("SetCompressor /SOLID lzma\n")
    sb.append("VIProductVersion \"").append(windowsNsisVersion(packageVersion)).append("\"\n")
    sb.append("VIAddVersionKey \"ProductName\" \"").append(app).append("\"\n")
    sb.append("VIAddVersionKey \"CompanyName\" \"").append(manufacturer).append("\"\n")
    sb.append("VIAddVersionKey \"FileDescription\" \"")
        .append(nsisEscape(description.ifBlank { applicationName }))
        .append("\"\n")
    sb.append("VIAddVersionKey \"FileVersion\" \"")
        .append(nsisEscape(packageVersion))
        .append("\"\n\n")
    sb.append("VIAddVersionKey \"LegalCopyright\" \"").append(manufacturer).append("\"\n\n")
    sb.append("Page directory\nPage instfiles\nUninstPage uninstConfirm\nUninstPage instfiles\n\n")
    sb.append("Section \"Install\"\n")
    sb.append("  SetShellVarContext all\n")
    sb.append("  SetRegView 64\n")
    sb.append("  SetOutPath \"\$INSTDIR\"\n")
    sb.append("  File /r \"")
        .append(nsisEscape(distributionDirectory.absolutePath))
        .append("\\*\"\n")
    sb.append("  WriteUninstaller \"\$INSTDIR\\Uninstall.exe\"\n")
    sb.append("  CreateDirectory \"\$SMPROGRAMS\\").append(app).append("\"\n")
    sb.append("  CreateShortCut \"\$SMPROGRAMS\\")
        .append(app)
        .append("\\")
        .append(app)
        .append(".lnk\" \"\$INSTDIR\\")
        .append(nsisEscape(executableName))
        .append(".exe\"\n")
    sb.append("  WriteRegStr HKLM \"")
        .append(uninstallKey)
        .append("\" \"DisplayName\" \"")
        .append(app)
        .append("\"\n")
    sb.append("  WriteRegStr HKLM \"")
        .append(uninstallKey)
        .append("\" \"DisplayVersion\" \"")
        .append(nsisEscape(packageVersion))
        .append("\"\n")
    sb.append("  WriteRegStr HKLM \"")
        .append(uninstallKey)
        .append("\" \"Publisher\" \"")
        .append(manufacturer)
        .append("\"\n")
    sb.append("  WriteRegStr HKLM \"")
        .append(uninstallKey)
        .append("\" \"InstallLocation\" \"\$INSTDIR\"\n")
    sb.append("  WriteRegStr HKLM \"")
        .append(uninstallKey)
        .append("\" \"UninstallString\" '\"\$INSTDIR\\Uninstall.exe\"'\n")
    sb.append("  WriteRegDWORD HKLM \"").append(uninstallKey).append("\" \"NoModify\" 1\n")
    sb.append("  WriteRegDWORD HKLM \"").append(uninstallKey).append("\" \"NoRepair\" 1\n")
    sb.append("SectionEnd\n\n")
    sb.append("Section \"Uninstall\"\n")
    sb.append("  SetShellVarContext all\n")
    sb.append("  SetRegView 64\n")
    sb.append("  Delete \"\$SMPROGRAMS\\").append(app).append("\\").append(app).append(".lnk\"\n")
    sb.append("  RMDir \"\$SMPROGRAMS\\").append(app).append("\"\n")
    sb.append("  DeleteRegKey HKLM \"").append(uninstallKey).append("\"\n")
    sb.append("  RMDir /r \"\$INSTDIR\"\n")
    sb.append("SectionEnd\n")
    return sb.toString()
}

internal fun windowsMsiVersion(version: String): String {
    val parts =
        version
            .substringBefore('-')
            .split('.')
            .take(3)
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .toMutableList()
    while (parts.size < 3) parts += 0
    require(parts[0] in 0..255 && parts[1] in 0..255 && parts[2] in 0..65535) {
        "Windows MSI version '" + version + "' is outside MSI's 255.255.65535 version range"
    }
    return parts.joinToString(".")
}

internal fun windowsNsisVersion(version: String): String {
    val parts = Regex("\\d+").findAll(version).map { it.value.toInt() }.take(4).toMutableList()
    while (parts.size < 4) parts += 0
    require(parts.all { it in 0..65535 }) {
        "Windows installer version '" + version + "' contains a component outside 0..65535"
    }
    return parts.joinToString(".")
}

private fun stableInstallerId(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

private fun nsisEscape(value: String): String =
    value.replace("$", "$$").replace("\"", "$\\\"").replace("\r", " ").replace("\n", " ")
