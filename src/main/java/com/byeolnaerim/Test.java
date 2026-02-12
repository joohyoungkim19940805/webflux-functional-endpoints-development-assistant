package com.byeolnaerim;


public class Test {

	public interface OnlyD {

		void d();

	}

	public interface OnlyE {

		void e();

	}

	public interface BothDE extends OnlyD, OnlyE {}

	public class QueryBuilderAccesser {

	}

	public class FindAllTest extends QueryBuilderAccesser {

		public OnlyD a() {

			return new InternalRunner();

		}

		public OnlyE b() {

			return new InternalRunner();

		}

		public BothDE c() {

			return new InternalRunner();

		}

		private class InternalRunner implements BothDE {

			@Override
			public void d() {

				System.out.println( "d 호출됨" );

			}

			@Override
			public void e() {

				System.out.println( "e 호출됨" );

			}

		}


	}

	public static void main(
		String[] args
	) {

		Test outer = new Test();
		FindAllTest test = outer.new FindAllTest();

		test.a().d(); // 가능
		// test.a().e(); // 컴파일 에러!

		test.b().e(); // 가능
		// test.b().d(); // 컴파일 에러!

		test.c().d(); // 가능
		test.c().e(); // 가능

	}

}
