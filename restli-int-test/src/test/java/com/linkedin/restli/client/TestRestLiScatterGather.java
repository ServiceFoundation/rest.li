/*
   Copyright (c) 2018 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package com.linkedin.restli.client;


import com.linkedin.d2.balancer.ServiceUnavailableException;
import com.linkedin.d2.balancer.URIMapper;
import com.linkedin.d2.balancer.util.hashing.HashRingProvider;
import com.linkedin.d2.balancer.util.hashing.RingBasedUriMapper;
import com.linkedin.d2.balancer.util.partitions.PartitionInfoProvider;
import com.linkedin.r2.RemoteInvocationException;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.transport.common.Client;
import com.linkedin.r2.transport.common.bridge.client.TransportClientAdapter;
import com.linkedin.r2.transport.http.client.HttpClientFactory;
import com.linkedin.restli.client.response.BatchKVResponse;
import com.linkedin.restli.client.util.RestLiClientConfig;
import com.linkedin.restli.common.BatchCreateIdResponse;
import com.linkedin.restli.common.BatchResponse;
import com.linkedin.restli.common.CreateIdStatus;
import com.linkedin.restli.common.EntityResponse;
import com.linkedin.restli.common.PatchRequest;
import com.linkedin.restli.common.UpdateStatus;
import com.linkedin.restli.examples.RestLiIntegrationTest;
import com.linkedin.restli.examples.greetings.api.Greeting;
import com.linkedin.restli.examples.greetings.api.Tone;
import com.linkedin.restli.examples.greetings.client.GreetingsBuilders;
import com.linkedin.restli.examples.greetings.client.GreetingsRequestBuilders;
import com.linkedin.restli.internal.common.TestConstants;
import com.linkedin.restli.test.util.RootBuilderWrapper;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.linkedin.d2.balancer.util.hashing.URIMapperTestUtil.createStaticHashRingProvider;
import static com.linkedin.d2.balancer.util.hashing.URIMapperTestUtil.getHashFunction;
import static com.linkedin.d2.balancer.util.hashing.URIMapperTestUtil.createHashBasedPartitionInfoProvider;


/**
 * Integration test for Rest.li Scatter Gather client based on URIMapper interface.
 *
 * @author Min Chen
 */
public class TestRestLiScatterGather extends RestLiIntegrationTest
{
  private static final Client CLIENT = new TransportClientAdapter(new HttpClientFactory.Builder().build().getClient(
    Collections.<String, String>emptyMap()));
  private static final String URI_PREFIX = "http://localhost:1338/";
  private static final RestClient REST_CLIENT = new AlwaysD2RestClient(CLIENT, URI_PREFIX);
  private static final String GREETING_URI_REG = "greetings/(.*)\\?";

  private static class AlwaysD2RestClient extends RestClient
  {
    AlwaysD2RestClient(Client client, String prefix)
    {
      super(client, prefix);
    }

    @Override
    protected <T> boolean needScatterGather(Request<T> request, RequestContext requestContext, ScatterGatherStrategy scatterGatherStrategy)
    {
      return (scatterGatherStrategy != null) && scatterGatherStrategy.needScatterGather(request);
    }
  }

  @BeforeClass
  public void initClass() throws Exception
  {
    super.init();
  }

  @AfterClass
  public void shutDown() throws Exception
  {

    super.shutdown();
  }

  @Test(dataProvider = TestConstants.RESTLI_PROTOCOL_1_2_PREFIX + "scatterGatherDataProvider")
  public static void testSendScatterGatherRequest(URIMapper mapper, RootBuilderWrapper<Long, Greeting> builders)
    throws RemoteInvocationException
  {
    REST_CLIENT.getClientConfig().setScatterGatherStrategy(new DefaultScatterGatherStrategy(mapper));

    final int NUM_IDS = 20;
    List<Greeting> entities = generateCreate(NUM_IDS);
    Long[] requestIds = prepareData(entities);

    // BATCH_GET
    testSendGetSGRequests(requestIds);
    testSendGetKVSGRequests(requestIds);

    // BATCH_UPDATE
    Map<Long, Greeting> input = generateUpdates(requestIds);
    testSendSGUpdateRequests(input, builders);

    // BATCH_PATIAL_UPDATE
    Map<Long, PatchRequest<Greeting>> patch = generatePartialUpdates(requestIds);
    testSendSGPartialUpdateRequests(patch, builders);

    // BATCH_DELETE
    testSendSGDeleteRequests(requestIds, builders);
  }

  private static Long[] prepareData(List<Greeting> entities)
    throws RemoteInvocationException
  {
    GreetingsRequestBuilders builders = new GreetingsRequestBuilders();
    BatchCreateIdRequest<Long, Greeting> request = builders.batchCreate().inputs(entities).build();
    Response<BatchCreateIdResponse<Long>> response = REST_CLIENT.sendRequest(request).getResponse();
    List<CreateIdStatus<Long>> statuses = response.getEntity().getElements();
    final Long[] requestIds = new Long[entities.size()];
    for (int i = 0; i < statuses.size(); ++i)
    {
      CreateIdStatus<Long> status = statuses.get(i);
      Assert.assertFalse(status.hasError());
      requestIds[i] = status.getKey();
    }
    return requestIds;
  }

  private static void testSendGetSGRequests(Long[] requestIds) throws RemoteInvocationException
  {
    BatchGetRequest<Greeting> request =
            new GreetingsBuilders().batchGet().ids(requestIds).fields(Greeting.fields().message()).setParam("foo", "bar").build();
    BatchResponse<Greeting> result = REST_CLIENT.sendRequest(request).getResponse().getEntity();
    Assert.assertEquals(result.getResults().size(), requestIds.length);
    Assert.assertEquals(result.getErrors().size(), 0);
  }

  private static void testSendGetKVSGRequests(Long[] requestIds) throws RemoteInvocationException
  {
    BatchGetEntityRequest<Long, Greeting> request =
            new GreetingsRequestBuilders().batchGet().ids(requestIds).fields(Greeting.fields().message()).setParam("foo", "bar").build();
    BatchKVResponse<Long, EntityResponse<Greeting>> result = REST_CLIENT.sendRequest(request).getResponse().getEntity();
    Assert.assertEquals(result.getResults().size(), requestIds.length);
    Assert.assertEquals(result.getErrors().size(), 0);
  }

  private static void testSendSGUpdateRequests(Map<Long, Greeting> inputs,
                                               RootBuilderWrapper<Long, Greeting> builders)
    throws RemoteInvocationException
  {
    @SuppressWarnings("unchecked")
    BatchUpdateRequest<Long, Greeting> request =
            (BatchUpdateRequest<Long, Greeting>) builders.batchUpdate().inputs(inputs).setParam("foo", "bar").build();
    BatchKVResponse<Long, UpdateStatus> result = REST_CLIENT.sendRequest(request).getResponse().getEntity();
    Assert.assertEquals(result.getResults().size(), inputs.size());
    Assert.assertEquals(result.getErrors().size(), 0);
  }

  private static void testSendSGPartialUpdateRequests(Map<Long, PatchRequest<Greeting>> inputs,
                                                      RootBuilderWrapper<Long, Greeting> builders)
          throws RemoteInvocationException
  {
    @SuppressWarnings("unchecked")
    BatchPartialUpdateRequest<Long, Greeting> request =
            (BatchPartialUpdateRequest<Long, Greeting>) builders.batchPartialUpdate().patchInputs(inputs).setParam("foo", "bar").build();
    BatchKVResponse<Long, UpdateStatus> result = REST_CLIENT.sendRequest(request).getResponse().getEntity();
    Assert.assertEquals(result.getResults().size(), inputs.size());
    Assert.assertEquals(result.getErrors().size(), 0);
  }

  private static void testSendSGDeleteRequests(Long[] requestIds,
                                               RootBuilderWrapper<Long, Greeting> builders)
    throws RemoteInvocationException
  {
    @SuppressWarnings("unchecked")
    BatchDeleteRequest<Long, Greeting> request =
            (BatchDeleteRequest<Long, Greeting>) builders.batchDelete().ids(requestIds).setParam("foo", "bar").build();
    BatchKVResponse<Long, UpdateStatus> result = REST_CLIENT.sendRequest(request).getResponse().getEntity();
    Assert.assertEquals(result.getResults().size(), requestIds.length);
    Assert.assertEquals(result.getErrors().size(), 0);
  }

  private static List<Greeting> generateCreate(int num)
  {
    List<Greeting> creates = new ArrayList<>();
    for (int i = 0; i < num; ++i)
    {
      Greeting greeting = new Greeting();
      greeting.setMessage("create message").setTone(Tone.FRIENDLY);
      creates.add(greeting);
    }
    return creates;
  }

  private static Map<Long, Greeting> generateUpdates(Long[] ids)
  {
    Map<Long, Greeting> updates = new HashMap<>();
    for (long l : ids)
    {
      Greeting greeting = new Greeting();
      greeting.setId(l).setMessage("update message").setTone(Tone.SINCERE);
      updates.put(l,greeting);
    }
    return updates;
  }

  private static Map<Long, PatchRequest<Greeting>> generatePartialUpdates(Long[] ids)
  {
    Map<Long, PatchRequest<Greeting>> patches = new HashMap<>();
    for (long l : ids)
    {
      patches.put(l, new PatchRequest<>());
    }
    return patches;
  }

  private static URIMapper getURIMapper(boolean sticky, boolean partitioned) throws ServiceUnavailableException
  {
    int partitionCount = partitioned ? 10 : 1;
    int totalHostCount = 100;

    HashRingProvider ringProvider =
            createStaticHashRingProvider(totalHostCount, partitionCount, getHashFunction(sticky));
    PartitionInfoProvider infoProvider = createHashBasedPartitionInfoProvider(partitionCount, GREETING_URI_REG);
    return new RingBasedUriMapper(ringProvider, infoProvider);
  }

  @DataProvider(name = TestConstants.RESTLI_PROTOCOL_1_2_PREFIX + "scatterGatherDataProvider")
  private static Object[][] scatterGatherDataProvider() throws ServiceUnavailableException
  {
    return new Object[][] {
            // partition Only
            { getURIMapper(false, true), new RootBuilderWrapper<Long, Greeting>(new GreetingsBuilders()) },
            { getURIMapper(false, true), new RootBuilderWrapper<Long, Greeting>(new GreetingsRequestBuilders()) },
            // sticky Only
            { getURIMapper(true, false), new RootBuilderWrapper<Long, Greeting>(new GreetingsBuilders()) },
            { getURIMapper(true, false), new RootBuilderWrapper<Long, Greeting>(new GreetingsRequestBuilders()) },
            // both sticky and partition
            { getURIMapper(true, true), new RootBuilderWrapper<Long, Greeting>(new GreetingsBuilders()) },
            { getURIMapper(true, true), new RootBuilderWrapper<Long, Greeting>(new GreetingsRequestBuilders()) },
            // neither sticky nor partition
            { getURIMapper(false, false), new RootBuilderWrapper<Long, Greeting>(new GreetingsBuilders()) },
            { getURIMapper(false, false), new RootBuilderWrapper<Long, Greeting>(new GreetingsRequestBuilders())}
    };
  }
}
