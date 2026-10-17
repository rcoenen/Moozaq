#!/usr/bin/env python3
"""Install and open the standalone Android app. No Mac engine or tunnel required."""
import argparse
from pathlib import Path
import subprocess

root=Path(__file__).resolve().parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',help='Exact ADB device serial, required when multiple devices are attached')
args=parser.parse_args()
devices=subprocess.check_output(['adb','devices'],text=True).splitlines()[1:]
online=[line.split()[0] for line in devices if len(line.split())>=2 and line.split()[1]=='device']
serial=args.serial
if serial is None:
    if len(online)!=1: raise SystemExit('Connect one phone or specify --serial. No device was modified.')
    serial=online[0]
if serial not in online: raise SystemExit('The requested phone is not connected/authorized.')
apk=root/'androidApp/build/outputs/apk/debug/androidApp-debug.apk'
if not apk.exists(): raise SystemExit('Build :androidApp:assembleDebug first.')
adb=['adb','-s',serial]
subprocess.run(adb+['install','-r',str(apk)],check=True)
# Retire only Moozaq's old companion tunnel, if present.
subprocess.run(adb+['reverse','--remove','tcp:38765'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
subprocess.run(adb+['shell','am','start','-n','com.moozaq.app/.MainActivity'],check=True)
print('Moozaq installed and opened. No playback command was sent.')
