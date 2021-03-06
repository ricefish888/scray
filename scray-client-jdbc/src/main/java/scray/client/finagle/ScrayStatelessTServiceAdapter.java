package scray.client.finagle;

import java.sql.SQLException;

import scray.service.qmodel.thriftjava.ScrayTQuery;
import scray.service.qmodel.thriftjava.ScrayUUID;
import scray.service.qservice.thriftjava.ScrayStatelessTService;
import scray.service.qservice.thriftjava.ScrayTResultFrame;

import com.twitter.finagle.Thrift;
import com.twitter.util.Await;
import com.twitter.util.Duration;
import com.twitter.util.Future;

public class ScrayStatelessTServiceAdapter implements ScrayTServiceAdapter {

	// adapter keeps paging state
	private int pageIndex = 0;

	protected ScrayStatelessTService.FutureIface client;
	private String endpoint;

	public ScrayStatelessTService.FutureIface getClient() {
		// lazy init
		if (client == null) {
			client = Thrift.newIface(endpoint,
					ScrayStatelessTService.FutureIface.class);
		}
		return client;
	}

	public ScrayStatelessTServiceAdapter(String endpoint) {
		this.endpoint = endpoint;
	}

	public ScrayUUID query(ScrayTQuery query, int queryTimeout)
			throws SQLException {
		try {
			Future<ScrayUUID> fuuid = getClient().query(query);
			return Await
					.result(fuuid, Duration.fromSeconds(queryTimeout));
		} catch (Exception e) {
			throw new SQLException(e);
		}
	}

	public ScrayTResultFrame getResults(ScrayUUID queryId, int queryTimeout)
			throws SQLException {
		try {
			Future<ScrayTResultFrame> fframe = getClient().getResults(queryId,
					pageIndex);
			ScrayTResultFrame frame = Await.result(fframe, Duration.fromSeconds(queryTimeout));
			pageIndex += 1;
			return frame;
		} catch (Exception e) {
			throw new SQLException(e);
		}
	}
}
