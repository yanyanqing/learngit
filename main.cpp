#include <cstdlib>
#include <xstring>
#include <cstdio>
#include <vector>
#include <iostream>

using namespace std;

template<typename _Arg, typename _Result>
struct my_unary_function
{
	typedef _Arg    argument_type;
	typedef _Result result_type;
};

template<typename _Arg1, typename _Arg2, typename _Result>
struct my_binary_function
{
	typedef _Arg1   first_argument_type;
	typedef _Arg2   second_argument_type;
	typedef _Result result_type;
};

template<typename _Ty>
struct plus : public my_unary_function<_Ty, _Ty>
{
	_Ty operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left + _Right;
	}
};

template<typename _Ty>
struct minus : public my_unary_function<_Ty, _Ty>
{
	_Ty operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left - _Right;
	}
};

template<typename _Ty>
struct multiplies : public my_unary_function<_Ty, _Ty>
{
	_Ty operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left * _Right;
	}
};

template<typename _Ty>
struct divides : public my_unary_function<_Ty, _Ty>
{
	_Ty operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left / _Right;
	}
};

template<typename _Ty>
struct modulus : public my_unary_function<_Ty, _Ty>
{
	_Ty operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left % _Right;
	}
};

template<typename _Ty>
struct negate : public my_unary_function<_Ty, _Ty>
{
	_Ty operator()(const _Ty& _Left) const
	{
		return ~_Left;
	}
};

template<typename _Ty>
struct equal_to : public my_binary_function<_Ty, _Ty, bool>
{
	bool operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left == _Right;
	}
};

template<typename _Ty>
struct not_equal_to : public my_binary_function<_Ty, _Ty, bool>
{
	bool operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left != _Right;
	}
};

template<typename _Ty>
struct greater : public my_binary_function<_Ty, _Ty, bool>
{
	bool operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left > _Right;
	}
};

template<typename _Ty>
struct less : public my_binary_function<_Ty, _Ty, bool>
{
	bool operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left < _Right;
	}
};

template<typename _Ty>
struct greater_equal : public my_binary_function<_Ty, _Ty, bool>
{
	bool operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left >= _Right;
	}
};

template<typename _Ty>
struct less_equal : public my_binary_function<_Ty, _Ty, bool>
{
	bool operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left <= _Right;
	}
};

template<typename _Ty>
struct logical_and : public my_binary_function<_Ty, _Ty, bool>
{
	bool operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left && _Right;
	}
};

template<typename _Ty>
struct logical_or : public my_binary_function<_Ty, _Ty, bool>
{
	bool operator()(const _Ty& _Left, const _Ty& _Right) const
	{
		return _Left || _Right;
	}
};

template<typename _Ty>
struct logical_not : public my_unary_function<_Ty, bool>
{
	bool operator()(const _Ty& _Left) const
	{
		return !_Left;
	}
};

template<typename _Fn1>
class unary_negate : public my_unary_function<typename _Fn1::argument_type, bool>
{
public:
	explicit unary_negate(const _Fn1& _Func):_Functor(_Func){}
	bool operator()(const typename _Fn1::argument_type _Left) const
	{
		return !_Functor(_Left);
	}
private:
	_Fn1 _Functor;
};

template<typename _Fn1>
inline unary_negate<_Fn1> not1(const _Fn1& _Func)
{
	return unary_negate<_Fn1>(_Func);
}

template<typename _Fn2>
class binary_negate 
	: public my_binary_function<typename _Fn2::first_argument_type,
	                            typename _Fn2::second_argument_type, bool>
{
public:
	explicit binary_negate(const _Fn2& _Func):_Functor(_Func){}
	bool operator()(const typename _Fn2::first_argument_type _Left,
					const typename _Fn2::second_argument_type _Right) const
	{
		return !_Functor(_Left, _Right);
	}
private:
	_Fn2 _Functor;
};

template<typename _Fn2>
inline binary_negate<_Fn2> not2(const _Fn2& _Func)
{
	return binary_negate<_Fn2>(_Func);
}

template<typename _Fn2>
class binder1st : public my_unary_function<typename _Fn2::second_argument_type,
										 typename _Fn2::result_type>
{
public:
	typedef my_unary_function<typename _Fn2::second_argument_type,
						   typename _Fn2::result_type> _Base;
	typedef typename _Base::argument_type argument_type;
	typedef typename _Base::result_type result_type;

	explicit binder1st(const _Fn2& _Func,
		               const typename _Fn2::first_argument_type& _Left)
					   :op(_Func), value(_Left){}
	result_type operator()(const argument_type& _Right)
	{
		return op(value, _Right);
	}
	result_type operator()(const argument_type& _Right) const
	{
		return op(value, _Right);
	}
private:
	_Fn2 op;
	typename _Fn2::first_argument_type value;
};

template<typename _Fn2, typename _Ty>
inline binder1st<_Fn2>
	bind1st(const _Fn2& _Func, const _Ty& _Left)
{
	typename _Fn2::first_argument_type _Val(_Left);
	return binder1st<_Fn2>(_Func, _Val);
}

template<typename _Fn2>
class binder2st : public my_unary_function<typename _Fn2::first_argument_type,
										 typename _Fn2::result_type>
{
public:
	typedef my_unary_function<typename _Fn2::first_argument_type,
						   typename _Fn2::result_type> _Base;
	typedef typename _Base::argument_type argument_type;
	typedef typename _Base::result_type result_type;

	explicit binder2st(const typename _Fn2::second_argument_type& _Right,
		               const _Fn2& _Func)
					   :_Functor(_Func), value(_Right){}
	result_type operator()(const argument_type& _Left)
	{
		return op(value, _Left);
	}
	result_type operator()(const argument_type& _Left) const
	{
		return op(value, _Left);
	}
private:
	_Fn2 op;
	typename _Fn2::second_argument_type value;
};

template<typename _Fn2, class _Ty>
inline binder2st<_Fn2> bind2st(const _Fn2& _Func, const _Ty& _Right)
{
	 typename _Fn2::second_argument_type& _Val(_Right);
	 return binder2st<_Fn2>(_Right,_Func);
}

template<typename Iterator, typename _Fn2>
void my_sort(const Iterator& first, const Iterator& last, const _Fn2& func)
{
	Iterator i,j;
	int k = 0;
	Iterator::value_type temp;

	for(i = first; i < last - 1; ++i, ++k)
	{
		for(j = first; j < last - 1 - k; ++j)
		{
			if(func(*j, *(j + 1)))
			{
				temp = *j;
				*j = *(j + 1);
				*(j + 1) = temp;
			}
		}
	}
}

template<typename Iterator, typename _Fn2>
Iterator my_find_if(Iterator& first, Iterator& last, const _Fn2& _func)
{
	for(; first != last; ++first)
	{
		if(_func(*first))
		{
			return first;
		}
	}

	return last;
}


int main()
{
	vector<int> vec;
	for(int i = 0; i < 10; ++i)
	{
		vec.push_back(rand() % 100 + 1);
	}

	my_sort(vec.begin(), vec.end(), greater<int>());

	vector<int>::iterator it = my_find_if(vec.begin(), vec.end(), 
										  not1(bind1st(greater<int>(), 50)));
	cout << *it << endl;
}
