#!/usr/bin/env python3
"""Verify archived patches and retained source hashes without modifying a checkout."""
import hashlib,json,os,pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parent
base='b53002f4e359a66d6ae87660920b97e437d7958e'
# During publication, the same verified public tree may be supplied explicitly.
base=os.environ.get('ARCHIVE_BASE_TREE',base)
chains={'r28':['r28'],'r29':['r28','r29'],'r30':['r28','r30'],'r31':['r28','r30','r31'],'continuation':['continuation']}
for label,chain in chains.items():
 with tempfile.TemporaryDirectory(prefix='filmroom-archive-') as tmp:
  env=dict(os.environ,GIT_INDEX_FILE=str(pathlib.Path(tmp)/'index'))
  def git(*args,input=None):return subprocess.check_output(['git',*args],env=env,input=input)
  git('read-tree',base)
  for part in chain:
   patch=str(root/(part+'.patch'));git('apply','--cached','--check',patch);git('apply','--cached',patch)
  tree=git('write-tree').decode().strip();manifest=json.loads((root/(label+'-manifest.json')).read_text());retained=0;omitted=[]
  for item in manifest:
   if item.get('excluded'):omitted.append(item['path']);continue
   data=git('show',tree+':'+item['path'])
   assert hashlib.sha256(data).hexdigest()==item['sha256'],(label,item['path'])
   retained+=1
  print(label,': PASS',retained,'retained file hashes;',len(omitted),'explicitly omitted fixture(s)')
