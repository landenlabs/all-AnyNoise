 ADB=~/Library/Android/sdk/platform-tools/adb                                                                                                                                                                
 PKG=com.landenlabs.all_anynoise                                                                                                                                                                             
 echo "=== disabling Private DNS temporarily ==="                                                                                                                                                            
 $ADB -s 16071FDD4000GK shell settings put global private_dns_mode off                                                                                                                                       
 sleep 2                                                                                                                                                                                                     
 $ADB -s 16071FDD4000GK logcat -c                                                                                                                                                                            
 $ADB -s 16071FDD4000GK shell am force-stop $PKG                                                                                                                                                             
 $ADB -s 16071FDD4000GK shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1                                                                                                           
 sleep 20                                                                                                                                                                                                    
 echo "=== firestore log after disabling private DNS ==="                                                                                                                                                    
 $ADB -s 16071FDD4000GK logcat -d | grep -i "firestore" | tail -20   

   $ADB -s 16071FDD4000GK shell settings get global private_dns_mode                                                                                                                                            
  echo "=== network interfaces ==="                                                                                                                                                                            
  $ADB -s 16071FDD4000GK shell ip addr show 2>/dev/null | grep -E "^[0-9]+:|tun|ppp"                                                                                                                           
  echo "=== installed ad-block / firewall / vpn apps ==="                                                                                                                                                      
  $ADB -s 16071FDD4000GK shell pm list packages | grep -iE "adguard|netguard|blokada|dns66|nextdns|firewall|vpn")             