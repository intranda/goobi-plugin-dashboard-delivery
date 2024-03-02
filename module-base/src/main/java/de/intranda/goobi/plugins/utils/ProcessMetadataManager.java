package de.intranda.goobi.plugins.utils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import org.apache.commons.dbutils.QueryRunner;
import org.goobi.beans.DatabaseObject;
import org.goobi.beans.Institution;
import org.goobi.beans.Process;

import de.sub.goobi.persistence.managers.MySQLHelper;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class ProcessMetadataManager extends ProcessManager{

    private static final long serialVersionUID = 5985402465514578025L;



    @Override
    public List<? extends DatabaseObject> getList(String order, String filter, Integer start, Integer count, Institution institution) {

        return getProcessesFromDB(order, filter, start, count);

    }



    private List<Process> getProcessesFromDB(String order, String filter, Integer start, Integer count) {
        Connection connection = null;
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT prozesse.* FROM prozesse left join batches on prozesse.batchID = batches.id ");
        sql.append("INNER JOIN projekte on prozesse.ProjekteID = projekte.ProjekteID ");
        sql.append("INNER JOIN institution on projekte.institution_id = institution.id ");
        sql.append("left join metadata md1 on md1.processid = prozesse.ProzesseID and md1.name='TitleDocMain' ");
        sql.append("left join metadata md2 on md2.processid = prozesse.ProzesseID and md2.name='Author' ");
        sql.append("left join metadata md3 on md3.processid = prozesse.ProzesseID and md3.name='PublicationYear' ");


        if (filter != null && !filter.isEmpty()) {
            sql.append(" WHERE " + filter);
        }
        if (order != null && !order.isEmpty()) {
            sql.append(" ORDER BY " + order);
        }
        if (start != null && count != null) {
            sql.append(" LIMIT " + start + ", " + count);
        }
        try {
            connection = MySQLHelper.getInstance().getConnection();
            if (log.isTraceEnabled()) {
                log.trace(sql.toString());
            }
            List<Process> ret = null;
            ret = new QueryRunner().query(connection, sql.toString(),  ProcessManager.resultSetToProcessListHandler);
            return ret;
        } catch (SQLException e) {
            log.error(e);
        } finally {
            if (connection != null) {
                try {
                    MySQLHelper.closeConnection(connection);
                } catch (SQLException e) {
                    log.error(e);
                }
            }
        }
        return Collections.emptyList();
    }



}
