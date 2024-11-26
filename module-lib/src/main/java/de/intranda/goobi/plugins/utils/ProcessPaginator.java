package de.intranda.goobi.plugins.utils;

import java.util.ArrayList;
import java.util.List;

import org.goobi.beans.DatabaseObject;
import org.goobi.managedbeans.DatabasePaginator;

import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.persistence.managers.IManager;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class ProcessPaginator extends DatabasePaginator {

    private static final long serialVersionUID = -7766654082744275319L;

    public ProcessPaginator(String order, String filter, IManager manager) {
        super(order, filter, manager, "");
        institution = null;
        pageSize = 1000;
        try {
            totalResults = manager.getHitSize(order, filter, null);
            load();
        } catch (DAOException e) {
            log.error("Failed to count results", e);
        }
    }

    @Override
    public void load() {
        try {
            results = manager.getList(order, filter, page * pageSize, pageSize, null);
            for (DatabaseObject d : results) {
                d.lazyLoad();
            }

        } catch (DAOException e) {
            log.error("Failed to load paginated results", e);
        }
    }

    @Override
    public List<? extends DatabaseObject> getCompleteList() {
        try {
            return manager.getList(order, filter, 0, Integer.MAX_VALUE, null);
        } catch (DAOException e) {
            log.error("Failed to load paginated results", e);
        }
        return new ArrayList<>();
    }

    @Override
    public List<Integer> getIdList() {
        if (idList.isEmpty()) {
            idList = manager.getIdList(order, filter, null);
        }
        return idList;
    }

}
