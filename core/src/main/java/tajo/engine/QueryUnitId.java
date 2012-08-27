package tajo.engine;

import tajo.common.ProtoObject;
import tajo.engine.TCommonProtos.QueryUnitIdProto;
import tajo.engine.TCommonProtos.QueryUnitIdProtoOrBuilder;

import java.text.NumberFormat;

/**
 * @author Hyunsik Choi
 */
public class QueryUnitId implements Comparable<QueryUnitId>, 
  ProtoObject<QueryUnitIdProto> {
  private static final NumberFormat format = NumberFormat.getInstance();
  static {
    format.setGroupingUsed(false);
    format.setMinimumIntegerDigits(6);
  }
  
  private ScheduleUnitId logicalId = null;
  private int id = -1;
  private String finalId = null;
  
  private QueryUnitIdProto proto = QueryUnitIdProto.getDefaultInstance();
  private QueryUnitIdProto.Builder builder = null;
  private boolean viaProto = false;
  
  public QueryUnitId() {
    builder = QueryUnitIdProto.newBuilder();
  }
  
  public QueryUnitId(final ScheduleUnitId logicalId,
      final int id) {
    this.logicalId = logicalId;
    this.id = id;
  }
  
  public QueryUnitId(QueryUnitIdProto proto) {
    this.proto = proto;
    viaProto = true;
  }
  
  public QueryUnitId(final String finalId) {
    this.finalId = finalId;
    int i = finalId.lastIndexOf(QueryId.SEPERATOR);
    this.logicalId = new ScheduleUnitId(finalId.substring(0, i));
    this.id = Integer.valueOf(finalId.substring(i+1));
  }
  
  public int getId() {
    QueryUnitIdProtoOrBuilder p = viaProto ? proto : builder;
    if (this.id != -1) {
      return this.id;
    }
    if (!p.hasId()) {
      return -1;
    }
    this.id = p.getId();
    return id;
  }
  
  public ScheduleUnitId getScheduleUnitId() {
    QueryUnitIdProtoOrBuilder p = viaProto ? proto : builder;
    if (this.logicalId != null) {
      return this.logicalId;
    }
    if (!p.hasScheduleUnitId()) {
      return null;
    }
    this.logicalId = new ScheduleUnitId(p.getScheduleUnitId());
    return this.logicalId;
  }
  
  public SubQueryId getSubQueryId() {
    return this.getScheduleUnitId().getSubQueryId();
  }
  
  public QueryId getQueryId() {
    return this.getSubQueryId().getQueryId();
  }
  
  @Override
  public final String toString() {
    if (finalId == null) {
      finalId = this.getScheduleUnitId() + 
          QueryId.SEPERATOR + format.format(getId());
    }
    return this.finalId;
  }
  
  @Override
  public final boolean equals(final Object o) {
    if (o instanceof QueryUnitId) {
      QueryUnitId other = (QueryUnitId) o;
      return this.toString().equals(other.toString());
    }    
    return false;
  }
  
  @Override
  public int hashCode() {
    return this.toString().hashCode();
  }

  @Override
  public final int compareTo(final QueryUnitId o) {
    return this.toString().compareTo(o.toString());
  }
  
  private void mergeProtoToLocal() {
    QueryUnitIdProtoOrBuilder p = viaProto ? proto : builder;
    if (logicalId == null) {
      logicalId = new ScheduleUnitId(p.getScheduleUnitId());
    }
    if (id == -1) {
      id = p.getId();
    }
  }

  @Override
  public void initFromProto() {
    mergeProtoToLocal();
  }
  
  private void mergeLocalToBuilder() {
    if (builder == null) {
      builder = QueryUnitIdProto.newBuilder(proto);
    }
    if (this.logicalId != null) {
      builder.setScheduleUnitId(logicalId.getProto());
    }
    if (this.id != -1) {
      builder.setId(id);
    }
  }

  @Override
  public QueryUnitIdProto getProto() {
    if (!viaProto) {
      mergeLocalToBuilder();
      proto = builder.build();
      viaProto = true;
    }
    return proto;
  }
}