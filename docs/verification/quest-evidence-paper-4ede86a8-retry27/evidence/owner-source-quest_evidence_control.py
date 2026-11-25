"""Small file-control transport for one owned acceptance boot; never starts a process."""
from pathlib import Path
import ctypes
import json
import os
import threading


def process_identity(pid):
    """Return a process creation token, or None if this precise process is absent."""
    if os.name != 'nt':
        try:
            return Path('/proc').joinpath(str(pid), 'stat').read_text().rsplit(')', 1)[1].split()[19]
        except (OSError, IndexError):
            return None
    from ctypes import wintypes
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD]
    kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.GetProcessTimes.argtypes=[wintypes.HANDLE]+[ctypes.POINTER(wintypes.FILETIME)]*4
    kernel.GetProcessTimes.restype=wintypes.BOOL
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handle=kernel.OpenProcess(0x1000,False,int(pid))
    if not handle:return None
    try:
        times=[wintypes.FILETIME() for _ in range(4)]
        if not kernel.GetProcessTimes(handle,*[ctypes.byref(value) for value in times]):return None
        return str((times[0].dwHighDateTime<<32)|times[0].dwLowDateTime)
    finally:kernel.CloseHandle(handle)


def decode_command(line,expected_sequence):
    if len(line)>8192:raise ValueError('Control command exceeds limit')
    def distinct_keys(pairs):
        result={}
        for key,value in pairs:
            if key in result:raise ValueError('Duplicate control JSON key')
            result[key]=value
        return result
    row=json.loads(line,object_pairs_hook=distinct_keys)
    if type(row) is not dict or set(row)!={'sequence','command'} or type(row['sequence']) is not int or row['sequence']!=expected_sequence:
        raise ValueError('Control sequence is missing, duplicated or out of order')
    command=row['command']
    if not isinstance(command,str) or not command or len(command)>4096 or any(ch in command for ch in '\r\n\0'):
        raise ValueError('Invalid control command line')
    return command


def watch_commands(path,events,stopped,owner_pid,owner_identity):
    """Append-only JSONL, one command per sequence. Partial trailing lines wait for a newline."""
    sequence=1;pending=b'';position=0
    try:
        with Path(path).open('rb') as source:
            original=os.fstat(source.fileno())
            while not stopped.is_set():
                if process_identity(owner_pid)!=owner_identity:raise RuntimeError('Acceptance coordinator process retired')
                actual=Path(path).stat()
                if (actual.st_dev,actual.st_ino)!=(original.st_dev,original.st_ino):raise RuntimeError('Control file was replaced')
                if actual.st_size<position:raise RuntimeError('Control file was truncated')
                data=source.read(8192)
                if data:
                    position+=len(data);pending+=data
                    if len(pending)>8192 and b'\n' not in pending:raise ValueError('Control line exceeds limit')
                    while b'\n' in pending:
                        line,pending=pending.split(b'\n',1)
                        command=decode_command(line.decode('utf-8'),sequence);sequence+=1
                        events.put(('input',command))
                else:stopped.wait(.1)
    except Exception as error:
        if not stopped.is_set():events.put(('control-error',type(error).__name__+': '+str(error)))
