PKGNAME="com.drdisagree.pixellauncherenhanced"
NEXUSLAUNCHER="com.google.android.apps.nexuslauncher"
LAUNCHER3="com.android.launcher3"
LSPDDBPATH="/data/adb/lspd/config/modules_config.db"
MAGISKDBPATH="/data/adb/magisk.db"

prepareAPK() {
  ui_print '- Unzipping APK...'
	unzip $ZIPFILE 'PLEnhanced.apk' -d $TMPDIR/ > /dev/null
	APKPATH="$TMPDIR/PLEnhanced.apk"
}

prepareSQL() {
	unzip $ZIPFILE sqlite3 -d $TMPDIR/ > /dev/null
	chmod +x $TMPDIR/sqlite3
	SQLITEPATH="$TMPDIR/sqlite3"
}

installAPK() {
  ui_print "- Installing PLEnhanced.apk..."

  if pm install "$APKPATH" > /dev/null 2>&1; then
    ui_print "- Installation successful."
  else
    if pm list packages | grep -q "$PKGNAME"; then
      ui_print "! Version code / signature mismatch."
      abort "! Uninstall existing PLEnhanced app and flash the module again."
    else
      ui_print "! Installation failed."
      abort "! Please unzip and install the APK manually."
    fi
  fi
}

launchApp() {
  sleep 1
  if pm list packages | grep -q "$PKGNAME"; then
    ui_print "- Launching PLEnhanced..."
    am start -n $PKGNAME/$PKGNAME.ui.activities.SplashActivity
  fi
}

runSQL() {
	SQLRESULT=$($SQLITEPATH $DBPATH "$CMD")
}

grantRootUID() {
	DBPATH=$MAGISKDBPATH

	CMD="insert into policies (uid, package_name, policy, until, logging, notification) values ($1, '$2', 2, 0, 1, 0);" && runSQL
	CMD="insert into policies (uid, policy, until, logging, notification) values ($1, 2, 0, 1, 0);" && runSQL
	CMD="update policies set policy = 2, until = 0, logging = 1, notification = 0 where uid = $1;" && runSQL
}

grantRootPkg() {
  if [ $(ls $MAGISKDBPATH) = $MAGISKDBPATH ]; then
    ui_print "- Granting root access to $1..."
    UID=$(pm list packages -U $1 --user 0 | grep ":$1 " | awk -F 'uid:' '{ print $2 }' | cut -d ',' -f 1)

    grantRootUID $UID $1
  fi
}

grantRootApps() {
	grantRootPkg $PKGNAME
}

activateModuleLSPD() {
  if [ $(ls $LSPDDBPATH) = $LSPDDBPATH ]; then
    DBPATH=$LSPDDBPATH
    PKGPATH=$(pm path $PKGNAME | cut -d':' -f2)

    ui_print '- Trying to activate the module in LSPosed...'

    CMD="select mid from modules where module_pkg_name like \"$PKGNAME\";" && runSQL
    OLDMID=$(echo $SQLRESULT | xargs)

    if [ $(($OLDMID+0)) -gt 0 ]; then
      CMD="select mid from modules where mid = $OLDMID and apk_path like \"$PKGPATH\" and enabled = 1;" && runSQL
      REALMID=$(echo $SQLRESULT | xargs)

      if [ $(($REALMID+0)) = 0 ]; then
        CMD="delete from scope where mid = $OLDMID;" && runSQL
        CMD="delete from modules where mid = $OLDMID;" && runSQL
      fi
    fi

    CMD="insert into modules (\"module_pkg_name\", \"apk_path\", \"enabled\") values (\"$PKGNAME\",\"$PKGPATH\", 1);" && runSQL

    CMD="select mid as ss from modules where module_pkg_name = \"$PKGNAME\";" && runSQL

    NEWMID=$(echo $SQLRESULT | xargs)

    CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"$NEXUSLAUNCHER\",0);" && runSQL
    CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"$LAUNCHER3\",0);" && runSQL
    CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"$PKGNAME\",0);" && runSQL

    sleep 1
    killall $NEXUSLAUNCHER
    killall $LAUNCHER3
    sleep 3
  else
    ui_print '! LSPosed not found!'
    ui_print '! This module will not work without LSPosed.'
    ui_print '! Please install LSPosed and reboot.'
    abort "! Aborting process."
  fi
}

finishInstallation() {
	launchApp
	ui_print ''
	ui_print '- Installation Complete!'
	ui_print ''
	ui_print ''
	ui_print '  ***************************************'
	ui_print '  * IGNORE THE FOLLOWING ERROR MESSAGE. *'
	ui_print '  ***************************************'
	ui_print ''
	abort ''
}

prepareAPK
installAPK
prepareSQL
grantRootApps
activateModuleLSPD
finishInstallation
