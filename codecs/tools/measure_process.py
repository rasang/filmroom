#!/usr/bin/env python3
"""Measure one child without logging its arguments/paths or copying image data."""
import os,sys,time,json
report,out,exe,*args=sys.argv[1:]
start=time.monotonic();pid=os.fork()
if pid==0:
 fd=os.open(out,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600);os.dup2(fd,1);os.close(fd);os.execv(exe,[exe,*args])
_,status,usage=os.wait4(pid,0)
data={'exitCode':os.waitstatus_to_exitcode(status),'elapsedSeconds':round(time.monotonic()-start,6),'peakRssKiB':usage.ru_maxrss,'userSeconds':usage.ru_utime,'systemSeconds':usage.ru_stime,'minorFaults':usage.ru_minflt,'majorFaults':usage.ru_majflt}
with open(report,'w')as f:json.dump(data,f,indent=2)
print(json.dumps(data))
sys.exit(data['exitCode'])
