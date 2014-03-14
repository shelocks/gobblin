package com.linkedin.uif.source.extractor.extract;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.linkedin.uif.configuration.ConfigurationKeys;
import com.linkedin.uif.configuration.WorkUnitState;
import com.linkedin.uif.source.extractor.Extractor;
import com.linkedin.uif.source.extractor.watermark.Predicate;
import com.linkedin.uif.source.extractor.watermark.WatermarkPredicate;
import com.linkedin.uif.source.extractor.watermark.WatermarkType;
import com.linkedin.uif.source.extractor.DataRecordException;
import com.linkedin.uif.source.extractor.exception.ExtractPrepareException;
import com.linkedin.uif.source.extractor.exception.HighWatermarkException;
import com.linkedin.uif.source.extractor.exception.RecordCountException;
import com.linkedin.uif.source.extractor.exception.SchemaException;
import com.linkedin.uif.source.extractor.schema.ArrayDataType;
import com.linkedin.uif.source.extractor.schema.DataType;
import com.linkedin.uif.source.extractor.schema.EnumDataType;
import com.linkedin.uif.source.extractor.schema.MapDataType;
import com.linkedin.uif.source.extractor.utils.Utils;
import com.linkedin.uif.source.workunit.WorkUnit;

/**
 * An implementation of Common extractor for all types of sources
 *
 * @param <D> type of data record
 * @param <S> type of schema
 */
public abstract class BaseExtractor<S, D> implements Extractor<S, D>, ProtocolSpecificLayer<S, D> {
	// default water mark format. example 20140301000000
	private static final SimpleDateFormat DEFAULT_WATERMARK_TIMESTAMP_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss");
	private static final Gson gson = new Gson();
	protected WorkUnitState workUnitState;
	protected WorkUnit workUnit;
	protected String workUnitName;
	private String entity;
	private String schema;
	private boolean fetchStatus = true;
	private S outputSchema;
	private long sourceRecordCount = 0;
	private long highWatermark;

	private Iterator<D> iterator;
	protected List<String> columnList = new ArrayList<String>();
	private List<Predicate> predicateList = new ArrayList<Predicate>();
	protected Log log = LogFactory.getLog(BaseExtractor.class);

	private S getOutputSchema() {
		return this.outputSchema;
	}

	protected void setOutputSchema(S outputSchema) {
		this.outputSchema = outputSchema;
	}

	private long getSourceRecordCount() {
		return sourceRecordCount;
	}

	public boolean getFetchStatus() {
		return fetchStatus;
	}

	public void setFetchStatus(boolean fetchStatus) {
		this.fetchStatus = fetchStatus;
	}

	public void setHighWatermark(long highWatermark) {
		this.highWatermark = highWatermark;
	}

	private boolean isPullRequired() {
		return getFetchStatus();
	}

	private boolean isInitialPull() {
		return iterator == null;
	}
	
	private void setWorkUnitName() {
		StringBuilder sb = new StringBuilder();
		sb.append("[");
		sb.append(Strings.nullToEmpty(this.workUnit.getProp(ConfigurationKeys.SOURCE_SCHEMA)));
		sb.append("_");
		sb.append(Strings.nullToEmpty(this.workUnit.getProp(ConfigurationKeys.SOURCE_ENTITY)));
		sb.append("_");
		String id = this.workUnitState.getId();
		int seqIndex = id.lastIndexOf("_",id.length());
		if(seqIndex>0) {
			sb.append(id.substring(seqIndex+1));
			sb.append("_");
			id = id.substring(0, seqIndex);
			int timeIndex = id.lastIndexOf("_",id.length());
			if(timeIndex>0) {
				sb.append(id.substring(timeIndex+1));
			}
		}
		sb.append("]");
		this.workUnitName = sb.toString();
	}
	
	protected String getWorkUnitName() {
		return this.workUnitName;
	}

	public BaseExtractor(WorkUnitState workUnitState) {
		this.workUnitState = workUnitState;
		this.workUnit = this.workUnitState.getWorkunit();
		this.schema = this.workUnit.getProp(ConfigurationKeys.SOURCE_SCHEMA);
		this.entity = this.workUnit.getProp(ConfigurationKeys.SOURCE_ENTITY);
		this.setWorkUnitName();
		this.log = LogFactory.getLog(BaseExtractor.class+this.getWorkUnitName());
	}

	/**
	 * Read a data record from source
	 * 
	 * @throws DataRecordException,IOException if it can't read data record
	 * @return record of type D
	 */
	@Override
	public D readRecord() throws DataRecordException, IOException {
		if (!this.isPullRequired()) {
			this.log.debug("No more records");
			return null;
		}

		D nextElement = null;
		try {
			if (isInitialPull()) {
				this.log.debug("initial pull");
				iterator = this.getRecordSet(this.schema, this.entity, this.workUnit, this.predicateList);
			}

			if (iterator.hasNext()) {
				nextElement = iterator.next();
				
				if(iterator.hasNext()) {
				} else {
					this.log.debug("next pull");
					iterator = this.getRecordSet(this.schema, this.entity, this.workUnit, this.predicateList);
					if (iterator == null) {
						this.setFetchStatus(false);
					}
				}
			}
		} catch (Exception e) {
			throw new DataRecordException("Failed to get records using rest api; error-" + e.getMessage());
		}
		return nextElement;
	};

	/**
	 * get source record count from source
	 * @return record count
	 */
	@Override
	public long getExpectedRecordCount() {
		return this.getSourceRecordCount();
	}

	/**
	 * get schema(Metadata) corresponding to the data records
	 * @return schema
	 */
	@Override
	public S getSchema() {
		return this.getOutputSchema();
	}

	/**
	 * get high watermark of the current pull
	 * @return high watermark
	 */
	@Override
	public long getHighWatermark() {
		return this.highWatermark;
	}

	/**
	 * close extractor read stream
	 * update high watermark
	 */
	@Override
	public void close() {
		this.workUnitState.setHighWaterMark(this.highWatermark);
	}
	
	/**
	 * @return full dump or not
	 */
	public boolean isFullDump() {
		return Boolean.valueOf(this.workUnit.getProp(ConfigurationKeys.EXTRACT_IS_FULL_KEY));
	}

	/**
	 * build schema, record count and high water mark
	 */
	public Extractor<S, D> build() throws ExtractPrepareException {	
		String watermarkColumn = this.workUnit.getProp(ConfigurationKeys.EXTRACT_DELTA_FIELDS_KEY);
		long lwm = this.workUnit.getLowWaterMark();
		long hwm = this.workUnit.getHighWaterMark();
		this.log.info("Low water mark:"+lwm+"; and High water mark:"+hwm);
		WatermarkType watermarkType;
		if(Strings.isNullOrEmpty(this.workUnit.getProp(ConfigurationKeys.SOURCE_WATERMARK_TYPE))) {
			watermarkType = null;
		} else {
			watermarkType = WatermarkType.valueOf(this.workUnit.getProp(ConfigurationKeys.SOURCE_WATERMARK_TYPE).toUpperCase());
		}	
		
		try {
			this.setTimeOut(this.workUnit.getProp(ConfigurationKeys.SOURCE_TIMEOUT));
			
			this.extractMetadata(this.schema, this.entity, this.workUnit);
			
			if(!Strings.isNullOrEmpty(watermarkColumn)) {
				this.highWatermark = this.getLatestWatermark(watermarkColumn, watermarkType, lwm, hwm);
				this.setRangePredicates(watermarkColumn, watermarkType, lwm, this.highWatermark);
			}
			
			this.sourceRecordCount = this.getSourceCount(this.schema, this.entity, this.workUnit, this.predicateList);
			
		} catch (SchemaException e) {
			throw new ExtractPrepareException("Failed to get schema for this object; error-" + e.getMessage());
		} catch (HighWatermarkException e) {
			throw new ExtractPrepareException("Failed to get high watermark; error-" + e.getMessage());
		} catch (RecordCountException e) {
			throw new ExtractPrepareException("Failed to get record count; error-" + e.getMessage());
		} catch (Exception e) {
			throw new ExtractPrepareException("Failed to prepare the extract build; error-" + e.getMessage());
		}
		return this;
	}

	/**
	 * if snapshot extract, get latest watermark else return work unit high watermark
     *
     * @param watermark column
     * @param low watermark value
     * @param high watermark value
     * @param column format
     * @return letst watermark
	 * @throws IOException 
	 */
	private long getLatestWatermark(String watermarkColumn, WatermarkType watermarkType, long lwmValue, long hwmValue)
			throws HighWatermarkException, IOException {
		
		if(!Boolean.valueOf(this.workUnit.getProp(ConfigurationKeys.SOURCE_SKIP_HIGH_WATERMARK_CALC))) {
			this.log.info("Getting high watermark");
			List<Predicate> list = new ArrayList<Predicate>();
			WatermarkPredicate watermark = new WatermarkPredicate(watermarkColumn, watermarkType);
			Predicate lwmPredicate = watermark.getPredicate(this, lwmValue, ">=");
			Predicate hwmPredicate = watermark.getPredicate(this, hwmValue, "<=");
			if (lwmPredicate != null) {
				list.add(lwmPredicate);
			}
			if (hwmPredicate != null) {
				list.add(hwmPredicate);
			}
			
			return this.getMaxWatermark(this.schema, this.entity, watermarkColumn, list, watermark.getWatermarkSourceFormat(this));
		}
		
		return hwmValue;
	}

	/**
	 * range predicates for watermark column and transaction columns.
	 * @param string 
	 * @param watermarkType 
     * @param watermark column
     * @param date column(for appends)
     * @param hour column(for appends)
     * @param batch column(for appends)
     * @param low watermark value
     * @param high watermark value
	 */
	private void setRangePredicates(String watermarkColumn, WatermarkType watermarkType, long lwmValue, long hwmValue) {
		this.log.info("Getting range predicates");
		WatermarkPredicate watermark = new WatermarkPredicate(watermarkColumn, watermarkType);
		this.addPredicates(watermark.getPredicate(this, lwmValue, ">="));
		this.addPredicates(watermark.getPredicate(this, hwmValue, "<="));
		
		if(Boolean.valueOf(this.workUnit.getProp(ConfigurationKeys.SOURCE_IS_HOURLY_EXTRACT))) {
			String hourColumn = this.workUnit.getProp(ConfigurationKeys.SOURCE_HOUR_COLUMN);
			if(!Strings.isNullOrEmpty(hourColumn)) {
				WatermarkPredicate hourlyWatermark = new WatermarkPredicate(hourColumn, WatermarkType.HOUR);
				this.addPredicates(hourlyWatermark.getPredicate(this, lwmValue, ">="));
				this.addPredicates(hourlyWatermark.getPredicate(this, hwmValue, "<="));
			}
		}
	}

	/**
	 * add predicate to the predicate list
	 * @param Predicate(watermark column, type, format and condition)
     * @return watermark list
	 */
	private void addPredicates(Predicate predicate) {
		if(predicate != null) {
			this.predicateList.add(predicate);
		}
	}
	
	/**
	 * True if the column is watermark column else return false
	 */
	protected boolean isWatermarkColumn(String watermarkColumn, String columnName) {
		if (columnName != null) {
			columnName = columnName.toLowerCase();
		}

		if (!Strings.isNullOrEmpty(watermarkColumn)) {
			List<String> waterMarkColumnList = Arrays.asList(watermarkColumn.toLowerCase().split(","));
			if (waterMarkColumnList.contains(columnName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Index of the primary key column from the given list of columns
	 * Return the position of column(starting from 1) if it is found in the given list of primarykey columns. return 0 if it is not found.
	 */
	protected int getPrimarykeyIndex(String primarykeyColumn, String columnName) {
		if (columnName != null) {
			columnName = columnName.toLowerCase();
		}

		if (!Strings.isNullOrEmpty(primarykeyColumn)) {
			List<String> primarykeyColumnList = Arrays.asList(primarykeyColumn.toLowerCase().split(","));
			return primarykeyColumnList.indexOf(columnName) + 1;
		}
		return 0;
	}

	/**
	 * True if it is metadata column else return false
	 */
	protected boolean isMetadataColumn(String columnName, List<String> columnList) {
		columnName = columnName.trim().toLowerCase();
		if (columnList.contains(columnName)) {
			return true;
		}
		return false;
	}

	/**
	 * Get intermediate form of data type using dataType map from source
	 */
	protected JsonObject convertDataType(String columnName, String type, String elementType, List<String> enumSymbols) {
		String dataType = this.getDataTypeMap().get(type);
		if (dataType == null) {
			dataType = "string";
		}
		DataType convertedDataType;
		if (dataType.equals("map")) {
			convertedDataType = new MapDataType(dataType, elementType);
		} else if (dataType.equals("array")) {
			convertedDataType = new ArrayDataType(dataType, elementType);
		} else if (dataType.equals("enum")) {
			convertedDataType = new EnumDataType(dataType, columnName, enumSymbols);
		} else {
			convertedDataType = new DataType(dataType);
		}

		return gson.fromJson(gson.toJson(convertedDataType), JsonObject.class).getAsJsonObject();
	}
}