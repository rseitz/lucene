package org.apache.lucene.index;

import org.apache.lucene.util.BytesRef;

public class TermWithOffset extends Term {

  private int startOffset = 0;

  public int getStartOffset() {
    return startOffset;
  }

  private TermWithOffset(String fld, BytesRef bytes) {
    super(fld);
    this.bytes = bytes == null ? null : BytesRef.deepCopyOf(bytes);
  }

  public TermWithOffset(String fld, BytesRef bytes, int startOffset) {
    super(fld);
    this.bytes = bytes == null ? null : BytesRef.deepCopyOf(bytes);
    this.startOffset = startOffset;
  }

  public TermWithOffset(String fld, String text, int startOffset) {
    this(fld, new BytesRef(text));
    this.startOffset = startOffset;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    TermWithOffset other = (TermWithOffset) obj;
    if (field == null) {
      if (other.field != null) return false;
    } else if (!field.equals(other.field)) return false;
    if (bytes == null) {
      if (other.bytes != null) return false;
    } else if (!bytes.equals(other.bytes)) return false;
    if (startOffset != other.startOffset) return false;
    return true;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((field == null) ? 0 : field.hashCode());
    result = prime * result + ((bytes == null) ? 0 : bytes.hashCode());
    result = prime * result + Integer.valueOf(startOffset).hashCode();
    return result;
  }

  @Override
  public String toString() {
    return field + ":" + text() + "[" + startOffset + "]";
  }
}
