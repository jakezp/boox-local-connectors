#!/usr/bin/env python3
"""Create an original, text-selectable PDF for explicit NeoReader sync validation."""
import argparse
from pathlib import Path


def make_pdf():
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>", b"", b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    pages = []
    for page in range(1, 5):
        page_id = len(objects) + 1
        pages.append(page_id)
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 420 595] /Resources << /Font << /F1 3 0 R >> >> /Contents {page_id + 1} 0 R >>".encode())
        stream = f"BT /F1 18 Tf 40 530 Td (BOOX Drive validation) Tj /F1 12 Tf 0 -35 Td (Page {page} of 4) Tj 0 -35 Td (Select this sentence to test highlights and book notes.) Tj 0 -30 Td (Add a bookmark and draw a line in the blank space.) Tj 0 -30 Td (This original document contains no personal data.) Tj ET\n".encode()
        objects.append(f"<< /Length {len(stream)} >>\nstream\n".encode() + stream + b"endstream")
    objects[1] = (f"<< /Type /Pages /Count {len(pages)} /Kids [" + " ".join(f"{p} 0 R" for p in pages) + "] >>").encode()
    result = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(result))
        result.extend(f"{index} 0 obj\n".encode() + obj + b"\nendobj\n")
    xref = len(result)
    result.extend(f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]:
        result.extend(f"{offset:010} 00000 n \n".encode())
    result.extend(f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(result)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    with args.output.open("xb") as output:
        output.write(make_pdf())
