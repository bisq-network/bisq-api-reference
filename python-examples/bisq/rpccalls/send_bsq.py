import grpc

# from getpass import getpass
import bisq.api.grpc_pb2 as bisq_messages
import bisq.api.grpc_pb2_grpc as bisq_service


def main():
    grpc_channel = grpc.insecure_channel('localhost:9998')
    grpc_service_stub = bisq_service.WalletsStub(grpc_channel)
    api_password: str = 'xyz'  # getpass("Enter API password: ")
    try:
        response = grpc_service_stub.SendBsq.with_call(
            bisq_messages.SendBsqRequest(
                address='Bbcrt1q9elrmtxtzpwt25zq2pmeeu6qk8029w404ad0xn',
                amount='10.00',
                tx_fee_rate='10'),
            metadata=[('password', api_password)])
        print('Response: ' + str(response[0].tx_info))
    except grpc.RpcError as rpc_error:
        print('gRPC API Exception: %s', rpc_error)


if __name__ == '__main__':
    main()
