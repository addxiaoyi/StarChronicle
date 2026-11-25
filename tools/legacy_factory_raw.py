"""Independent implementation of the legacy-factory-audit-v1 property digest."""
import hashlib
import struct

def fingerprint(domain, raw):
    data=bytearray()
    def text(value):
        encoded=value.encode('utf-16-be','surrogatepass')
        data.extend(struct.pack('>I',len(encoded)//2));data.extend(encoded)
    text('legacy-factory-audit-v1');text(domain);data.extend(struct.pack('>I',len(raw)))
    for key in sorted(raw,key=lambda k:k.encode('utf-16-be','surrogatepass')):
        text(key);text(raw[key])
    return hashlib.sha256(data).hexdigest()
